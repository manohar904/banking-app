package com.bank.gateway;

import java.io.Serializable;

/** The signed-in user held in the session. Carries the ids that access checks need. */
public record SignedInUser(long userId, String email, String role, Long customerId) implements Serializable {
}
