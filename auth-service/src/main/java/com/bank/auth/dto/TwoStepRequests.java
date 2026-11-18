package com.bank.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** What the two-step sign-in settings take and give back. */
public final class TwoStepRequests {

    private TwoStepRequests() {
    }

    public record Code(@NotBlank String code) {
    }

    /** Turning it off needs the password, so someone at an unlocked computer can't. */
    public record TurnOff(@NotBlank String password) {

        @Override
        public String toString() {
            return "TurnOff[]";
        }
    }

    /** available is false when the bank has no email set up, so codes can't be sent. */
    public record Status(boolean enabled, boolean available, String email) {
    }
}
