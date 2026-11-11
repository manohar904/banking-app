package com.bank.account.card;

/** A purchase over the available credit, or a payment over what is owed. Answered with a 422. */
public class CardLimitExceededException extends RuntimeException {

    public CardLimitExceededException(String message) {
        super(message);
    }
}
