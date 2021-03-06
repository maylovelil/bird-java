package com.bird.eventbus.handler;

import com.bird.eventbus.arg.IEventArg;
import com.bird.eventbus.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

/**
 * @author liuxx
 * @date 2019/1/16
 */
@Slf4j
public class EventDispatcher {

    @Autowired(required = false)
    private IEventHandlerStore handlerStore;

    @Autowired(required = false)
    private Collection<IEventHandlerInterceptor> interceptors;

    @Autowired
    private ApplicationContext applicationContext;

    @Value("${spring.application.name:}")
    private String application;
    /**
     * 消费结果队列
     */
    private BlockingQueue<EventHandleResult> resultQueue = new LinkedBlockingQueue<>();

    /**
     * 事件处理线程池
     */
    private ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * topic与处理方法映射关系
     */
    private final static ConcurrentMap<String, Set<Method>> EVENT_HANDLER_CONTAINER = new ConcurrentHashMap<>();

    @PostConstruct
    public void initHandlerStoreThread() {
        if (handlerStore != null) {
            ScheduledThreadPoolExecutor poolExecutor = new ScheduledThreadPoolExecutor(2, (new BasicThreadFactory.Builder()).build());
            poolExecutor.scheduleAtFixedRate(new EventHandleStoreConsumer(), 0, 10, TimeUnit.SECONDS);
        }
    }

    /**
     * 事件进入队列
     *
     * @param eventArg 事件信息
     */
    public void enqueue(IEventArg eventArg) {
        if (eventArg == null) {
            log.warn("接受到事件为null");
            return;
        }
        executor.submit(() -> this.handleEvent(eventArg));
    }

    /**
     * 根据包名初始化Handler信息
     *
     * @param packageName 包名
     */
    public void initWithPackage(String packageName) {
        if (StringUtils.isBlank(packageName)) {
            log.warn("eventbus监听器初始化报名不存在");
            return;
        }

        List<EventHandlerDefinition> definitions = new ArrayList<>();
        Set<Class<?>> classes = ClassUtils.getClasses(packageName);
        if (classes != null) {
            for (Class<?> clazz : classes) {
                for (Method method : clazz.getDeclaredMethods()) {
                    EventHandler eventAnnotation = method.getAnnotation(EventHandler.class);
                    if (eventAnnotation == null) continue;

                    //被@EventHandler注解标注的方法只接受一个参数，且参数必须是IEventArg的子类
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes.length != 1 || !IEventArg.class.isAssignableFrom(parameterTypes[0])) continue;

                    String argClassName = parameterTypes[0].getName();
                    Set<Method> eventHandlers = EVENT_HANDLER_CONTAINER.computeIfAbsent(argClassName, p -> new HashSet<>());
                    eventHandlers.add(method);
                    EVENT_HANDLER_CONTAINER.put(argClassName, eventHandlers);

                    EventHandlerDefinition definition = new EventHandlerDefinition();
                    definition.setClazz(clazz.getName());
                    definition.setMethod(method.getName());
                    definition.setEvent(argClassName);
                    definition.setGroup(application);
                    definitions.add(definition);
                }
            }
        }
        if (handlerStore != null && !CollectionUtils.isEmpty(definitions)) {
            handlerStore.initialize(definitions);
        }
    }


    /**
     * 获取当前程序所有事件的名称
     * 如果开启EventBus，且未处理任何的事件，返回默认topics
     *
     * @return 事件名称集合
     */
    public String[] getAllTopics() {
        Set<String> keys = EVENT_HANDLER_CONTAINER.keySet();
        if (CollectionUtils.isEmpty(keys)) {
            return new String[]{"none-topic"};
        }
        return keys.toArray(new String[0]);
    }

    private void handleEvent(IEventArg eventArg) {
        if (eventArg == null) return;

        EventHandleResult handleResult = new EventHandleResult(eventArg);
        handleResult.setGroup(application);
        EventHandleStatusEnum status = EventHandleStatusEnum.FAIL;
        int span = 24 * 60 * 60 * 1000;
        if (System.currentTimeMillis() - eventArg.getEventTime().getTime() > span) {
            status = EventHandleStatusEnum.TIMEOUT;
        } else {
            String eventKey = eventArg.getClass().getName();
            Set<Method> methods = EVENT_HANDLER_CONTAINER.getOrDefault(eventKey, null);
            if (!CollectionUtils.isEmpty(methods)) {
                int successCount = 0;
                for (Method method : methods) {
                    EventHandleResult.ConsumerResult itemResult = this.invokeMethod(method, eventArg, handleResult);
                    handleResult.addItem(itemResult);
                    if (itemResult.getSuccess()) {
                        successCount++;
                    }
                }
                if (successCount >= handleResult.getItems().size()) {
                    status = EventHandleStatusEnum.SUCCESS;
                } else if (successCount > 0) {
                    status = EventHandleStatusEnum.PARTIAL_SUCCESS;
                }
            }
        }
        handleResult.setStatus(status);
        if (handlerStore != null) {
            try {
                resultQueue.put(handleResult);
            } catch (InterruptedException ex) {
                log.error("事件消费结果入队失败", ex);
            }
        }
    }

    /**
     * 执行事件处理方法
     *
     * @param method   处理程序方法
     * @param eventArg 事件参数
     */
    private EventHandleResult.ConsumerResult invokeMethod(Method method, IEventArg eventArg, EventHandleResult handleResult) {

        EventHandleResult.ConsumerResult consumerResult = handleResult.new ConsumerResult();

        Class typeClass = method.getDeclaringClass();
        try {
            Object instance = applicationContext.getBean(typeClass);

            this.interceptBefore(method, eventArg);

            method.invoke(instance, eventArg);

            this.interceptAfter(method, eventArg);
            consumerResult.setSuccess(true);
        } catch (InvocationTargetException e) {
            this.interceptException(method, eventArg, e);

            consumerResult.setSuccess(false);
            consumerResult.setMessage(e.getTargetException().getMessage());
            log.error("事件消费失败", e);
        } catch (Exception e) {
            this.interceptException(method, eventArg, e);

            consumerResult.setSuccess(false);
            consumerResult.setMessage(e.getLocalizedMessage());
            log.error("事件消费失败", e);
        }

        consumerResult.setClazz(typeClass.getName());
        consumerResult.setMethod(method.getName());
        return consumerResult;
    }


    /**
     * 执行事件处理前拦截方法
     *
     * @param method   执行的方法
     * @param eventArg 事件消息
     */
    private void interceptBefore(Method method, IEventArg eventArg) {
        if (interceptors != null) {
            for (IEventHandlerInterceptor interceptor : interceptors) {
                interceptor.beforeHandle(eventArg, method);
            }
        }
    }

    /**
     * 执行事件处理后拦截方法
     *
     * @param method   执行的方法
     * @param eventArg 事件消息
     */
    private void interceptAfter(Method method, IEventArg eventArg) {
        if (interceptors != null) {
            for (IEventHandlerInterceptor interceptor : interceptors) {
                interceptor.afterHandle(eventArg, method);
            }
        }
    }

    /**
     * 执行事件处理异常拦截方法
     *
     * @param method   执行的方法
     * @param eventArg 事件消息
     */
    private void interceptException(Method method, IEventArg eventArg, Exception ex) {
        if (interceptors != null) {
            for (IEventHandlerInterceptor interceptor : interceptors) {
                interceptor.onException(eventArg, method, ex);
            }
        }
    }

    /**
     * 事件处理结果存储结果消费者
     */
    private class EventHandleStoreConsumer implements Runnable {
        @Override
        public void run() {
            List<EventHandleResult> results = new ArrayList<>();
            resultQueue.drainTo(results);

            if (CollectionUtils.isEmpty(results)) return;

            try {
                if (handlerStore != null) {
                    handlerStore.store(results);
                }
            } catch (Exception ex) {
                log.error("保存EventBus消费结果失败：" + ex.getMessage());
            }
        }
    }
}
