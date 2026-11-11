package com.bank.customer.help;

/** Whose turn it is. A closed request takes no more messages; the customer opens a new one. */
public enum HelpStatus {
    WAITING_FOR_BANK,
    ANSWERED,
    CLOSED
}
