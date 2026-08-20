package com.myplus.catalog.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PERF-8 — the three fields a product PICKER needs, and nothing else.
 *
 * <h3>Why a second product DTO exists</h3>
 * {@code ProductDTO} carries 23 fields because a product form needs them. A {@code <select>} needs an id, a
 * label and a price. Measured on the demo tenant: a full DTO is <b>538 bytes</b> and this projection is
 * <b>92</b> — so serving the picker from {@code ProductDTO} sends <b>83% of the payload to be discarded by the
 * browser</b>, including {@code description} (up to 2 000 characters), four timestamps and the stamped
 * last-rate fields.
 *
 * <p>All five pickers on the platform were checked before this was written — {@code business.js} ×2,
 * {@code order-booking.js}, {@code pharma.js}, {@code quarantine.js} — and none reads a field outside these.
 *
 * <p><b>There is deliberately no {@code isActive}.</b> The endpoint filters to active rows in SQL, so the
 * field would be a constant {@code true} on every row: a byte per product spent restating what the query
 * already guarantees. Today each caller downloads the inactive products and hides them in JavaScript, which is
 * the same work done twice and transferred once too often.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductPickerDTO {

    private Long id;

    private String name;

    /** Drives the sale screen's price prefill; the picker's {@code data-price} attribute. */
    private BigDecimal sellingPrice;
}
