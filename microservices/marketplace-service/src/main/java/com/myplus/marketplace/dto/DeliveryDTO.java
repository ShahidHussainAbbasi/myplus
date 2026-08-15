package com.myplus.marketplace.dto;

import java.util.List;

import lombok.Data;

/**
 * OMS O7 D4 — the delivery outcome the warehouse admin keys from a signed invoice.
 *
 * <p>One parcel, one outcome. The per-line quantities are what the shop actually took; anything short of what
 * went out is credited back, which is why they are required rather than optional — "how much arrived" is the
 * question this screen exists to answer, and defaulting it to "all of it" would reproduce the Ship form's
 * original sin (the fastest correct-LOOKING action being the unverified one).
 */
@Data
public class DeliveryDTO {

    /** Which parcel. Null is refused: an order may have several, and they are settled separately. */
    private Long shipmentId;

    /** The driver, as a note — not an identity, and not evidence. */
    private String deliveredBy;

    /** PAID | PART_PAID | CREDIT. */
    private String settlement;

    /** What the driver actually collected. Zero for a pure credit sale. */
    private java.math.BigDecimal amountCollected;

    private String note;

    private List<Line> lines;

    /** Out only — what the keying produced. */
    private String outcome;
    private List<String> creditNotes;
    private String recordedByName;
    private java.time.LocalDateTime recordedAt;

    @Data
    public static class Line {
        private Long orderItemId;
        /** How much of this line the shop TOOK. The rest is credited back. */
        private Integer deliveredQuantity;
    }
}
