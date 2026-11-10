package com.bank.gateway;

import com.bank.common.security.InternalTokens;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * The gateway's public key at /actuator/jwks on its private management port, which serves HTTPS in
 * compose.yaml. The services fetch it from here rather than from the main port, which browsers use
 * and which is plain HTTP, so nobody on the way can swap in a key of their own.
 */
@Component
@Endpoint(id = "jwks")
class InternalKeysEndpoint {

    private final InternalTokens tokens;

    InternalKeysEndpoint(InternalTokens tokens) {
        this.tokens = tokens;
    }

    @ReadOperation
    Map<String, Object> publicKeys() {
        return tokens.publicKeys();
    }
}
