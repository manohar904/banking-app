package com.bank.auth.dto;

public record CredentialsRequest(String email, String password) {

    /** Keeps the password out of logs and error messages. */
    @Override
    public String toString() {
        return "CredentialsRequest[email=" + email + "]";
    }
}
