package com.bank.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmPasswordResetRequest(
        @NotBlank @Size(max = 100) String token,
        @NotBlank @Size(min = 12, max = 72) String newPassword) {

    /** Keeps the token and password out of logs and error messages. */
    @Override
    public String toString() {
        return "ConfirmPasswordResetRequest[]";
    }
}
