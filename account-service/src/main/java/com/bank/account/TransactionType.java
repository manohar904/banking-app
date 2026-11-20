package com.bank.account;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER_OUT,
    TRANSFER_IN,
    LOAN_PAYOUT,
    LOAN_REPAYMENT,
    CARD_PAYMENT,
    /** Interest paid on a savings account. */
    INTEREST;

    /** What statements, the money in and out chart and spending alerts count as money out. */
    public static final Set<TransactionType> MONEY_OUT = Collections.unmodifiableSet(
            EnumSet.of(WITHDRAWAL, TRANSFER_OUT, LOAN_REPAYMENT, CARD_PAYMENT));
}
