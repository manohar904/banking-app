package com.bank.gateway;

import com.bank.common.events.HealthHeartbeat;
import com.bank.common.events.KafkaPublishingConfig;
import com.bank.common.observability.TracingConfig;
import com.bank.common.security.InternalClients;
import com.bank.common.security.InternalTokens;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({InternalTokens.class, InternalClients.class, KafkaPublishingConfig.class, HealthHeartbeat.class,
        TracingConfig.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
