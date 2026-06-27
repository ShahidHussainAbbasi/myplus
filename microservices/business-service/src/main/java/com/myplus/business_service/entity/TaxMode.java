package com.myplus.business_service.entity;

/**
 * How a product's price relates to tax (G3 tax engine, slice 35).
 * <ul>
 *   <li>{@code EXCLUSIVE} — the price is pre-tax; tax is added on top.</li>
 *   <li>{@code INCLUSIVE} — the price already includes tax; tax is backed out of it.</li>
 * </ul>
 */
public enum TaxMode {
    EXCLUSIVE,
    INCLUSIVE
}
