package com.myplus.marketplace.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OMS O8 — a delivery round's recovery sheet: what the salesman takes out, and what the cashier reconciles.
 *
 * <h3>The document this replaces</h3>
 * The client's own <i>Net Sales Summary</i>, printed and then covered in handwriting down the right margin —
 * {@code CR} where a shop took the goods on credit, an amount where the salesman received one. The pen was
 * doing the work the sheet did not: recording the collection. So the columns that were blank paper become
 * printed columns, and the totals the sheet never had become a control.
 *
 * <h3>Why the totals live on this object and are not left to the browser</h3>
 * The foot of this sheet is what the cash bag is counted against. A total assembled in JavaScript from rendered
 * rows is a total that can silently disagree with the rows the server actually sent — a filtered page, a
 * rounding step, a row that failed to draw. Sent from here, the figures the cashier reconciles against and the
 * figures the sheet lists are the same arithmetic.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoundSheetDTO {

    /** The day (or first day) the round covers — what prints in the heading. */
    private LocalDate from;
    private LocalDate to;

    /** Who is carrying it, when the round was filtered to one person. Null = every dispatch in the window. */
    private String salesman;

    @Builder.Default
    private List<Stop> stops = new ArrayList<>();

    // ── the control total: the whole point of the foot of the sheet ───────────────────────────────────────

    /** How many stops are on the round. Printed so a missing page is obvious. */
    private int stopCount;

    /** Σ invoice totals — what left the warehouse in value terms. */
    private BigDecimal invoiceTotal;

    /**
     * Σ what each account owes, this delivery included — the figure the round is collecting against.
     *
     * <p>Deliberately NOT Σ invoice totals. A recovery run collects arrears as well as today's goods, and a
     * sheet that showed only the new invoices would let a shop settle the delivery while an older balance
     * rolled on untouched. That is how distribution debt quietly ages.
     */
    private BigDecimal totalDue;

    /** One shop on the round. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stop {

        /** Position on the sheet, 1-based — the {@code Sr} column the salesman ticks down. */
        private int sr;

        /**
         * The order behind this stop — what slice 5 keys against.
         *
         * <p>Not shown on the printed sheet: a shopkeeper matches on the INVOICE number, which is what they are
         * holding. But the screen the marked-up sheet is typed into needs an identity to post against, and
         * re-deriving it from the order number would be a lookup per stop for something already in hand.
         */
        private Long orderId;

        private String orderNo;
        /** The invoice the shop is holding. This is what the salesman and the shopkeeper match on. */
        private String invoiceNo;
        private LocalDate date;

        private Long customerId;
        /** The account the debt sits on — a branch shows its trade GROUP's name, because that is whose it is. */
        private String accountName;
        /**
         * Where the shop is. Doubles as the AREA column: the client encodes area inside the account name today
         * (`LABAIK PHARMACY ~ ZAHIR PER`), and until Customer has a territory field this is the honest source.
         */
        private String area;

        /** What this delivery was invoiced at. */
        private BigDecimal invoiceTotal;
        /**
         * What the account owed BEFORE this invoice — derived as (total owed − this invoice's unpaid part)
         * rather than stored, so the two can never disagree. The same derivation the printed invoice uses for
         * its own Previous Balance line.
         */
        private BigDecimal previousBalance;
        /** Everything owed now, this delivery included. The figure to ask for. */
        private BigDecimal totalDue;
    }
}
