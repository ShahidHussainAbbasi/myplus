package com.web.dto.business;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/**
 * INST-1 — the plan block a sale carries when the cashier sells on terms.
 *
 * <h3>⚠ This class has a TWIN in business-service and they must change together</h3>
 * {@code com.myplus.business_service.dto.InstallmentPlanDTO} is the same shape. THIS is the copy that
 * decides what survives the hop: {@code SellController} binds {@code @RequestBody CustomerHistoryDTO} here and
 * then <b>re-serialises it</b> to business-service, so a field business-service declares and this class does
 * not is <b>silently discarded in transit</b> — the sale succeeds, the invoice is correct, and the plan simply
 * never exists. Nothing errors and no test fails.
 *
 * <p>That is design note F2, and it is why both files are always edited in one commit.
 *
 * <h3>Money is BigDecimal, without exception</h3>
 * The monolith's {@code CustomerHistoryDTO} carries {@code Float paidAmount} and {@code Float dueAmount}
 * alongside {@code BigDecimal tradeDiscount} — pre-existing debt beside a newer field that got it right.
 * Every field here follows {@code tradeDiscount} (governing standard §1.5), never {@code paidAmount}.
 */
@Data
public class InstallmentPlanDTO {

    /**
     * The item's price as the plan is written against it. Sent by the client rather than derived from the
     * cart because the plan may finance <b>one</b> line of a mixed basket — a shop sells a handset on terms
     * and a case for cash on the same receipt, and only the handset is financed.
     */
    private BigDecimal cashPrice;

    /** Taken at the counter. Settles immediately through the ordinary tender path; never scheduled. */
    private BigDecimal downPayment;

    private Integer installmentCount;

    /**
     * {@code monthly} / {@code fortnightly} / {@code weekly}.
     *
     * <p>Resolved through {@code Frequency.fromSetting}, which tolerates case and falls back to monthly —
     * matching what {@code SettingsService.getChoice} does with the tenant default, so a value typed here and
     * a value stored in settings can never behave differently.
     */
    private String frequency;

    /** When the first payment is expected. The whole schedule is measured from this anchor. */
    private LocalDate firstDueDate;

    /**
     * INST-6, and it must be zero or absent today.
     *
     * <p>Carried so the wire shape does not change when markup lands, but {@code PlanTerms.validate()}
     * refuses a non-zero value. Accepting the number and dropping it would let a shop believe it was
     * financing at a margin it is not earning — markup is finance income, not goods revenue, and posting it
     * to {@code 4000 Sales} would tax the financing.
     */
    private BigDecimal markupAmount;

    /**
     * The IMEI, as free text (INST-1). A <b>label</b>, not a register — INST-5 replaces it with a real
     * per-unit row in inventory-service that can enforce uniqueness and carry a status.
     */
    private String assetRef;

    private String notes;

    /**
     * R4 — the people standing behind this plan. Empty or null when the shop asks for none.
     *
     * <p>⚠ Must stay identical to the business-service twin. This list is re-serialised on its way through,
     * so a guarantor that exists only on one side of the hop is dropped without a sound.
     */
    private java.util.List<GuarantorDTO> guarantors;
}
