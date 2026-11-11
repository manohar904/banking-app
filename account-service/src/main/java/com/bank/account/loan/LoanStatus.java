package com.bank.account.loan;

public enum LoanStatus {
    /** Asked for, waiting for staff. */
    REQUESTED,
    REJECTED,
    /** The customer withdrew the request before staff decided. */
    CANCELLED,
    /** Paid out, being repaid. */
    ACTIVE,
    PAID_OFF
}
