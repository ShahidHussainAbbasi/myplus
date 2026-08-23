package com.myplus.business_service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.myplus.business_service.entity.Installment;
import com.myplus.business_service.entity.InstallmentPlan;

import lombok.Data;

/**
 * INST-1 — a plan as a screen needs it: the terms, the schedule, and what is actually owed.
 *
 * <p>A read DTO rather than the entity. Returning {@code InstallmentPlan} would ship {@code organizationId}
 * and the raw row id to the browser — the §1.5 breach the OMS programme had to fix twice, once in D1 and
 * again in D5 after D4 reintroduced it.
 *
 * <p><b>{@code overdue} is computed here, not stored.</b> The entity has no OVERDUE status by design: it is
 * {@code dueDate < today AND outstanding > 0}, and a stored flag would need a nightly job to keep true — on
 * the day that job does not run, every screen quietly shows stale truth. The screen and the reminder scanner
 * therefore evaluate the same predicate and cannot disagree.
 */
@Data
public class InstallmentPlanViewDTO {

    private Long id;
    private String planNo;
    private String invoiceNo;
    private String status;

    /**
     * INST-2 — who owes it. Resolved by the controller, not stored on the plan.
     *
     * <p>Not denormalised onto {@code installment_plan}: unlike an INVOICE, which must print the name it was
     * issued with even after a rename, a worklist should show who the customer IS today. That is the
     * opposite call to {@code CustomerHistory.bookedByName} and it is deliberate — a document is a record,
     * a worklist is a view.
     */
    private String customerName;

    private BigDecimal cashPrice;
    private BigDecimal downPayment;
    private BigDecimal financedAmount;
    private Integer installmentCount;
    private String frequency;
    private LocalDate firstDueDate;
    private LocalDate finalDueDate;
    private String assetRef;

    /** Derived from the rows — the figure that must equal the plan invoice's outstanding balance. */
    private BigDecimal totalOutstanding;
    private BigDecimal totalPaid;

    /** How many installments are late as at the date the read was taken. */
    private long overdueCount;

    private List<InstallmentViewDTO> installments = new ArrayList<>();

    @Data
    public static class InstallmentViewDTO {
        private Long id;
        private Integer seqNo;
        private LocalDate dueDate;
        private BigDecimal amount;
        private BigDecimal paidAmount;
        private BigDecimal outstanding;
        private String status;
        /** Derived, never stored — see the class javadoc. */
        private boolean overdue;
        private long daysOverdue;
    }

    /**
     * @param asOf the tenant's "today" — passed in rather than read from a clock inside the mapper, so the
     *             view is testable without freezing time and so an org timezone can be supplied once
     *             {@code org.timezone} exists
     */
    public static InstallmentPlanViewDTO of(InstallmentPlan p, LocalDate asOf) {
        return of(p, asOf, null);
    }

    /** @param customerName who owes it, resolved by the caller in ONE batched read */
    public static InstallmentPlanViewDTO of(InstallmentPlan p, LocalDate asOf, String customerName) {
        InstallmentPlanViewDTO d = new InstallmentPlanViewDTO();
        d.setId(p.getId());
        d.setPlanNo(p.getPlanNo());
        d.setInvoiceNo(p.getInvoiceNo());
        d.setStatus(p.getStatus());
        d.setCustomerName(customerName);
        d.setCashPrice(p.getCashPrice());
        d.setDownPayment(p.getDownPayment());
        d.setFinancedAmount(p.getFinancedAmount());
        d.setInstallmentCount(p.getInstallmentCount());
        d.setFrequency(p.getFrequency());
        d.setFirstDueDate(p.getFirstDueDate());
        d.setFinalDueDate(p.getFinalDueDate());
        d.setAssetRef(p.getAssetRef());
        d.setTotalOutstanding(p.getTotalOutstanding());
        d.setTotalPaid(p.getTotalPaid());
        d.setOverdueCount(p.overdueCount(asOf));

        for (Installment i : p.getInstallments()) {
            InstallmentViewDTO v = new InstallmentViewDTO();
            v.setId(i.getId());
            v.setSeqNo(i.getSeqNo());
            v.setDueDate(i.getDueDate());
            v.setAmount(i.getAmount());
            v.setPaidAmount(i.getPaidAmount());
            v.setOutstanding(i.getOutstanding());
            v.setStatus(i.getStatus());
            v.setOverdue(i.isOverdue(asOf));
            v.setDaysOverdue(i.daysOverdue(asOf));
            d.getInstallments().add(v);
        }
        return d;
    }
}
