package com.bank.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetPasswordRequest(@NotBlank @Size(min = 12, max = 72) String password) {
}
