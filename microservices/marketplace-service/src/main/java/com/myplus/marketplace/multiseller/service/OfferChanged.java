package com.myplus.marketplace.multiseller.service;

/**
 * MKT-1c — "this offer's published row may need fresh stock". Published inside the writing transaction and handled
 * AFTER COMMIT (caching standard K2): the remote stock read never runs inside a database transaction, and never for
 * a write that rolled back.
 */
public record OfferChanged(Long offerId) {
}
