package com.myplus.business_service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import com.myplus.business_service.entity.Installment;
import com.myplus.business_service.entity.InstallmentPlan;
import com.myplus.business_service.entity.InstallmentReminder;

import lombok.Data;

/**
 * INST-3a — one line on the collections worklist: who to ring, about what, and whether we already did.
 *
 * <p>A read DTO rather than the entity. Returning {@code InstallmentReminder} would ship
 * {@code organizationId} and raw row ids to the browser — the §1.5 breach the OMS programme had to fix twice.
 *
 * <p>{@code contact} is on here for the obvious reason: a worklist whose entire purpose is "ring these
 * people" and which does not show a phone number would send the shopkeeper to another screen for every single
 * row.
 */
@Data
public class ReminderViewDTO {

    private Long id;

    private String customerName;
    /** The number to ring. The only field on this DTO the shopkeeper physically acts on. */
    private String contact;

    private String planNo;
    private Integer seqNo;

    /** DUE_SOON | OVERDUE. */
    private String stage;
    private LocalDate dueDate;

    /** What is still owed on THIS installment — not the plan's whole balance. */
    private BigDecimal amountDue;

    /**
     * Days past due as at the read, derived. Negative for a {@code DUE_SOON} row means days still to go, which
     * is what lets one column serve both stages without the screen doing arithmetic.
     */
    private long daysOverdue;

    private LocalDateTime noticedAt;
    private LocalDateTime actedAt;
    private String outcome;
    private String note;

    /** Has anyone rung yet? The flag the worklist sorts and filters on. */
    private boolean actioned;

    public static ReminderViewDTO of(InstallmentReminder r, InstallmentPlan plan, Installment inst,
                                     com.myplus.business_service.entity.Customer customer, LocalDate asOf) {
        ReminderViewDTO d = new ReminderViewDTO();
        d.setId(r.getId());
        if (customer != null) {
            // Resolved live, not denormalised onto the reminder: a worklist is a VIEW and should show who the
            // customer IS today, which is the opposite call to an invoice and deliberately so.
            d.setCustomerName(customer.getName());
            d.setContact(customer.getContact());
        }
        d.setStage(r.getStage());
        d.setDueDate(r.getDueDate());
        d.setNoticedAt(r.getNoticedAt());
        d.setActedAt(r.getActedAt());
        d.setOutcome(r.getOutcome());
        d.setNote(r.getNote());
        d.setActioned(r.isActioned());

        if (plan != null) d.setPlanNo(plan.getPlanNo());
        if (inst != null) {
            d.setSeqNo(inst.getSeqNo());
            // The LIVE outstanding, read from the installment — never a figure copied onto the reminder when
            // it was recorded. A customer who part-paid yesterday must not be rung for the old amount.
            d.setAmountDue(inst.outstanding());
        }
        if (r.getDueDate() != null && asOf != null) {
            d.setDaysOverdue(ChronoUnit.DAYS.between(r.getDueDate(), asOf));
        }
        return d;
    }
}
