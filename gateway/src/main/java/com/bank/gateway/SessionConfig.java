package com.bank.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * Sessions are kept in Redis (Spring Session), so the gateway can restart, or run as more than one
 * copy, without signing anyone out. Redis connection settings are in application.yaml.
 */
@Configuration
@Slf4j
public class SessionConfig {

    /**
     * Java serialization, as Spring Session uses by default, except that a value this version cannot
     * read, such as a session saved by an older gateway with a changed class, counts as missing. That
     * person is then simply signed out, instead of every one of their requests failing.
     */
    @Bean
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        JdkSerializationRedisSerializer java = new JdkSerializationRedisSerializer(getClass().getClassLoader());
        return new RedisSerializer<>() {
            @Override
            public byte[] serialize(Object value) {
                return java.serialize(value);
            }

            @Override
            public Object deserialize(byte[] bytes) {
                try {
                    return java.deserialize(bytes);
                } catch (SerializationException e) {
                    log.info("Ignoring a session value this version cannot read: {}", e.getMostSpecificCause().toString());
                    return null;
                }
            }
        };
    }
}
