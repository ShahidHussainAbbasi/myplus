package com.myplus.marketplace.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** OMS O5b — one parcel: what went out, when, with whom, and under what tracking number. */
@Data
public class ShipmentDTO {

    private Long id;
    /** Merchant- and customer-facing reference, e.g. {@code SHP-000045}. */
    private String shipmentNo;
    private String carrier;
    private String trackingNumber;
    private String status;              // DISPATCHED | DELIVERED | CANCELLED
    private LocalDateTime shippedAt;
    private String note;
    private List<Line> lines;

    /** How much of one order line travelled in this parcel. */
    @Data
    public static class Line {
        private Long orderItemId;
        private Integer quantity;
        /** Filled on read so the UI need not join back to the order. */
        private String productName;
    }

    /** The dispatch request: which lines, how many of each, and the carrier details. */
    @Data
    public static class Request {
        private String carrier;
        private String trackingNumber;
        private String note;
        private List<LineRequest> lines;
    }

    @Data
    public static class LineRequest {
        private Long orderItemId;
        private Integer quantity;
        /** OMS O5d — set by the pack workbench when the quantity came from scans rather than typing. */
        private Boolean verified;
    }
}
