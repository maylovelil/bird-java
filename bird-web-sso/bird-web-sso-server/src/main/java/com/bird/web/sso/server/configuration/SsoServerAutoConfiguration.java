package com.bird.web.sso.server.configuration;

import com.bird.web.sso.SsoConstant;
import com.bird.web.sso.server.SsoServer;
import com.bird.web.sso.server.controller.TicketController;
import com.bird.web.sso.server.ticket.CacheTicketSessionStore;
import com.bird.web.sso.server.ticket.DesTicketProtector;
import com.bird.web.sso.server.ticket.ITicketProtector;
import com.bird.web.sso.server.ticket.ITicketSessionStore;
import com.bird.web.sso.server.SsoServerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author liuxx
 * @date 2019/3/5
 */
@Configuration
@ConditionalOnProperty(value = SsoConstant.SERVER_COOKIE_NAME)
public class SsoServerAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = SsoConstant.PREFIX_SERVER)
    public SsoServerProperties serverProperties() {
        return new SsoServerProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = SsoConstant.SERVER_USE_SESSION_STORE, havingValue = "true", matchIfMissing = true)
    public ITicketSessionStore ticketSessionStore() {
        return new CacheTicketSessionStore(serverProperties().getExpire());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = SsoConstant.SERVER_USE_SESSION_STORE, havingValue = "false")
    public ITicketProtector ticketProtector() {
        return new DesTicketProtector();
    }

    @Bean
    public SsoServer ssoServer(SsoServerProperties serverProperties) {
        if (serverProperties.getUseSessionStore()) {
            return new SsoServer(serverProperties, ticketSessionStore());
        } else {
            return new SsoServer(serverProperties, ticketProtector());
        }
    }

    @Bean
    public TicketController ticketController(SsoServer ssoServer){
        return new TicketController(ssoServer);
    }
}
