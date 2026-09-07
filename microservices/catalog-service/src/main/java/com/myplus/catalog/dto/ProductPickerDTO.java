package com.myplus.catalog.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PERF-8 — the few fields a product PICKER needs, and nothing else.
 *
 * <h3>Why a second product DTO exists</h3>
 * {@code ProductDTO} carries 23 fields because a product form needs them. A {@code <select>} needs an id, a
 * label and a price. Measured on the demo tenant: a full DTO is <b>538 bytes</b> and this projection is
 * <b>92</b> — so serving the picker from {@code ProductDTO} sends <b>83% of the payload to be discarded by the
 * browser</b>, including {@code description} (up to 2 000 characters), four timestamps and the stamped
 * last-rate fields.
 *
 * <p>All five pickers on the platform were checked before this was written — {@code business.js} ×2,
 * {@code order-booking.js}, {@code pharma.js}, {@code quarantine.js} — and none read a field outside these.
 * SER-6 added {@code requiresSerial} when the sale screen gained a reason to know before the line is added;
 * it is a boolean, so the projection is still a fraction of {@code ProductDTO}, and it is measured against
 * that bar rather than added because it was convenient.
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

    /**
     * ⭐ SER-6 — does this product carry a serial/IMEI? Drives whether the sale screen SHOWS the serial box.
     *
     * <p>Without it the box was on every line of every till, so a shop selling tablets and handsets from one
     * screen asked for an IMEI on the paracetamol. The alternative was a {@code /productStock} round trip per
     * selection just to read one boolean — on the hot path, for a field the picker payload can carry for a
     * few bytes.
     *
     * <p>Nullable on the column, so it arrives as {@code null} for pre-migration rows; the client treats
     * anything but {@code true} as "no serial", which is the safe reading — a product nobody has flagged is
     * not a tracked one.
     */
    private Boolean requiresSerial;
}
