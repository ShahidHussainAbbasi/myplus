package com.myplus.finance.service;

/**
 * Thrown when a transaction dated in a closed (locked) period is attempted.
 *
 * <p>A {@link com.myplus.common.web.exception.ValidationException}: a closed period is a business refusal, so it is
 * answered <b>400 with this message</b>. As a bare RuntimeException it reached the catch-all as a 500 "Something went
 * wrong", and callers could not tell it from an outage — expense postings retried it 20 times and then showed no
 * reason, and an expense bill paid into a closed period was told "the books did not answer" and left pending
 * (EX-1b, finding E1).
 */
public class PeriodClosedException extends com.myplus.common.web.exception.ValidationException {
    public PeriodClosedException(String message) { super(message); }
}
