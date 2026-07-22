package com.myplus.finance.service;

/** Thrown when a transaction dated in a closed (locked) period is attempted. */
public class PeriodClosedException extends RuntimeException {
    public PeriodClosedException(String message) { super(message); }
}
