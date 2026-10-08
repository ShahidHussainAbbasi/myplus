package com.myplus.marketplace.multiseller.dto;

import java.time.LocalDate;
import java.util.List;

/** MKT-2e — the seller scorecard on the wire (slice doc {@code mkt-2e-merchant-performance.md}). */
public final class PerformanceDTOs {

    private PerformanceDTOs() {
    }

    /**
     * One seller over the window. A rate is null when fewer than {@code minOrders} orders stand behind it.
     * {@code flags}: LOW_ACCEPTANCE, LATE_DELIVERY, COD_OVERDUE; a record for the operator, never a sanction.
     */
    public record SellerScore(Long organizationId, String sellerName, int accepted, int missed, int disputed,
            int excused, Double acceptanceRate, Long avgMinutesToAccept, int due, int onTime, Double onTimeRate,
            long sellerFaultReturns, boolean codOverdue, List<String> flags) {
    }

    /** The window (orders placed from {@code from}, {@code days} days) and the thresholds the flags use. */
    public record PerformanceView(int days, LocalDate from, int minOrders, double lowAcceptance, double lowOnTime,
            List<SellerScore> sellers) {
    }
}
