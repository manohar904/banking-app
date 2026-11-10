package com.bank.common.security;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public half of this part's signing key, so that the others can check its tokens. */
@RestController
public class InternalKeysController {

    private final InternalTokens tokens;

    public InternalKeysController(InternalTokens tokens) {
        this.tokens = tokens;
    }

    @GetMapping(InternalTokens.PUBLIC_KEYS_PATH)
    public Map<String, Object> publicKeys() {
        return tokens.publicKeys();
    }
}
