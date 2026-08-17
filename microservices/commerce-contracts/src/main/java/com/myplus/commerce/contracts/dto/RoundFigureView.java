package com.myplus.commerce.contracts.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The money on one line of a delivery round's recovery sheet, answered by the BOOKS.
 *
 * <h3>Why the sheet's figures come from here and not from the order</h3>
 * A route sheet is a collection document: the salesman uses it to ask each shop for money. Every number on it
 * is therefore an accounting figure, and the one system entitled to state what a shop owes is the one holding
 * the receivable. marketplace knows which orders went out on the round — that is genuinely its business — but
 * if it also computed the balances the sheet would be a second opinion about the same debt, and the salesman
 * would be collecting against a figure the ledger does not recognise.
 *
 * <p>So the division is: the channel says <b>which invoices are on the round</b>, and the books say
 * <b>what each one is worth and what the shop owes</b>.
 *
 * <h3>Why one call for the whole round</h3>
 * A round is 20–30 stops. Fetching a balance per stop is 30 round trips to build one sheet, on the screen a
 * cashier opens at the end of every single day. The batch shape is the point of this DTO existing at all.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoundFigureView {

    /** The invoice this line is for — the key the caller matched on. */
    private String invoiceNo;

    /** The outlet, so the caller can group a round by shop without a second lookup. */
    private Long customerId;

    /**
     * The account the debt actually sits on.
     *
     * <p>For a branch of a trade group this is the GROUP's name, not the branch's, because that is whose
     * balance is being reported — the same rule the credit check at booking follows. A salesman told to
     * collect from "Labaik Pharmacy 2" against the group's balance would ask the wrong shop for the money.
     */
    private String accountName;

    /** What this invoice was raised for, GROSS of nothing — the figure printed on the shop's copy. */
    private BigDecimal invoiceTotal;

    /**
     * Still unpaid ON THIS INVOICE. Zero once settled.
     *
     * <p>Kept separate from {@link #customerOutstanding} so the sheet can show "previous balance" as the
     * difference, rather than storing a second figure that could disagree with it. Same derivation the printed
     * invoice uses for its own Previous Balance line.
     */
    private BigDecimal invoiceOutstanding;

    /**
     * Everything the account owes right now, this invoice included.
     *
     * <p>This is the number the salesman is collecting against, and the reason the sheet shows it: a recovery
     * run is not only about today's delivery. A sheet listing only the new invoice lets a shop settle today's
     * goods while an older balance rolls on untouched, which is how distribution debt quietly ages.
     *
     * <p>Present whether or not the outlet has a credit limit. The booking screen's credit banner deliberately
     * says nothing for an uncapped customer — a shop with no limit is not "at 0% of 0" — but a collection sheet
     * must still state what they owe, so this comes from the outstanding balance itself rather than from the
     * limit check.
     */
    private BigDecimal customerOutstanding;
}
