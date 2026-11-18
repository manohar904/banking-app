package com.bank.auth.dto;

import java.util.UUID;

/** A sign-in code as typed, and which code it answers. */
public record CodeCheckRequest(UUID codeId, String code) {

    /** Keeps the code out of logs and error messages. */
    @Override
    public String toString() {
        return "CodeCheckRequest[codeId=" + codeId + "]";
    }
}
