package com.myplus.marketplace.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OMS O8 slice 5 — keying a whole round back in from the marked-up sheet.
 *
 * <h3>The document this mirrors</h3>
 * The salesman comes back with the route sheet, one line per stop, an amount written against each — or "CR"
 * where the shop paid nothing. This is that column, typed. One request per round rather than per stop, because
 * that is how the paper works and because 29 stops should not be 29 saves an operator can get half way through.
 *
 * <h3>What it deliberately does NOT carry</h3>
 * Per-line returned quantities. The sheet has no column for them — its columns are Received and Balance — so
 * keying a round assumes <b>everything dispatched was delivered</b>. A stop where goods came back is the
 * exception and is keyed on that order individually, where the line detail belongs; the round keying then finds
 * it already delivered and skips it, and says so. Guessing a return from a short payment would be inventing a
 * credit note nobody wrote down.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoundKeyDTO {

    /** Who carried the sheet. Free text: often a driver rather than a system user. */
    private String salesman;

    /**
     * What the cashier actually counted out of the bag.
     *
     * <p>Kept separate from the sum of the stops on purpose — the difference between the two IS the variance,
     * and a settlement that derived the count from the declarations could never show one.
     */
    private BigDecimal countedAmount;

    private String depositReference;
    /** Required by the settlement whenever the count does not match what was declared. */
    private String note;

    @Builder.Default
    private List<Stop> stops = new ArrayList<>();

    /** One line of the sheet, as typed. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stop {
        private Long orderId;
        /** What the shop paid. Zero or null is the "CR" case — goods delivered, nothing collected. */
        private BigDecimal amountCollected;
    }

    /** What happened, stop by stop, so the operator can see the round they just keyed. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Result {

        /** Stops whose delivery was recorded by this request. */
        private int keyed;

        /**
         * Stops that could not be keyed, each with a reason.
         *
         * <p>Reported rather than thrown, because a round is keyed as one action and an operator needs to see
         * WHICH shop is wrong, not have the whole batch refused over one of them. The commonest entry is
         * "already keyed" — which is what makes running this twice safe.
         */
        @Builder.Default
        private List<String> skipped = new ArrayList<>();

        /** Set once the cash was settled. Null when nothing was collected, or when settling was not attempted. */
        private String settlementNo;
        @Builder.Default
        private List<String> receipts = new ArrayList<>();

        /** Σ of what the stops declared — what the counted amount is measured against. */
        private BigDecimal declared;
    }
}
