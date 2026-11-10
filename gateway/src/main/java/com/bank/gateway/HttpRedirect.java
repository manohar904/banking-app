package com.bank.gateway;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * When the gateway serves HTTPS, also listens for plain HTTP on bank.http-redirect.port, only to send
 * browsers on to the HTTPS address (GatewaySecurityConfig does the sending). So old links and
 * bookmarks to http://localhost:8080 keep working. Off unless that setting is there, as in tests.
 */
@Configuration
@ConditionalOnProperty(HttpRedirect.PORT)
class HttpRedirect {

    static final String PORT = "bank.http-redirect.port";

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> plainHttpConnector(@Value("${" + PORT + "}") int port) {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(port);
            factory.addAdditionalConnectors(connector);
        };
    }
}
