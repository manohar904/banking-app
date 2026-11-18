package com.bank.gateway;

import java.util.UUID;
import org.springframework.security.core.AuthenticationException;

/** The password was right, and a code was emailed that must be entered to finish signing in. */
public class SignInCodeRequiredException extends AuthenticationException {

    private final transient UUID codeId;
    private final String sentTo;

    SignInCodeRequiredException(UUID codeId, String sentTo) {
        super("A sign-in code was sent.");
        this.codeId = codeId;
        this.sentTo = sentTo;
    }

    UUID codeId() {
        return codeId;
    }

    String sentTo() {
        return sentTo;
    }
}
