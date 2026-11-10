package com.bank.account;

/** A customer's transfer would take them past what they may send in one day. */
public class TransferLimitExceededException extends RuntimeException {

    public TransferLimitExceededException(String message) {
        super(message);
    }
}
