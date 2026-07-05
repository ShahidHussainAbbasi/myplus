package com.myplus.business_service.service;

/**
 * A sale was rejected because a line has less sellable stock than requested (FEFO reserve returned OUT_OF_STOCK).
 * This is a business rejection, NOT an unexpected error: nothing has been written yet (reserve runs before the
 * PENDING write), so the sell endpoint surfaces {@link #getMessage()} to the cashier verbatim instead of the
 * generic "unexpected error" envelope.
 */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
