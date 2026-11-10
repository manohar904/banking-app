package com.bank.auth.dto;

/** clientAddress is the browser's address as the gateway saw it. Empty when the caller does not know it. */
public record CredentialsRequest(String email, String password, String clientAddress) {

    /** Keeps the password out of logs and error messages. */
    @Override
    public String toString() {
        return "CredentialsRequest[email=" + email + ", clientAddress=" + clientAddress + "]";
    }
}
