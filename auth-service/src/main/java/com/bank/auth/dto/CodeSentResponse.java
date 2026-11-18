package com.bank.auth.dto;

import java.util.UUID;

/** A code was emailed. sentTo shows enough of the address to recognise it, not the whole of it. */
public record CodeSentResponse(UUID codeId, String sentTo) {

    /** "manohar@example.com" becomes "m******@example.com". */
    public static String hide(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "*" + email.substring(Math.max(at, 0));
        }
        return email.charAt(0) + "*".repeat(at - 1) + email.substring(at);
    }
}
