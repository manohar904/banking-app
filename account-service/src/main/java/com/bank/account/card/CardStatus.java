package com.bank.account.card;

public enum CardStatus {
    ACTIVE,
    /** Turned off for now by the customer or staff: no purchases, but it can still be paid. */
    FROZEN,
    /** For good. Only possible when nothing is owed. */
    CLOSED
}
