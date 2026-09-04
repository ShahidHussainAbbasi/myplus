package com.myplus.business_service.controller;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.dto.InstallmentPlanViewDTO;
import com.myplus.business_service.entity.InstallmentPlan;
import com.myplus.business_service.service.InstallmentPlanService;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

/**
 * INST-1 — reading installment plans.
 *
 * <p>Ships WITH the slice rather than after it. A capability the UI cannot reach is review finding R7, which
 * this programme has hit three times — each one a feature built, tested, and reachable by nobody. The
 * monolith proxy lands in the same commit for the same reason.
 *
 * <p>Returns DTOs, never entities: an entity would carry {@code organizationId} and the raw row id to the
 * browser, which §1.5 forbids and which the OMS programme had to fix twice.
 */
@RestController
public class InstallmentController {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(InstallmentController.class);

    @Autowired private InstallmentPlanService installmentPlanService;
    @Autowired private com.myplus.business_service.service.InstallmentReminderService reminderService;
    @Autowired private com.myplus.business_service.service.RepossessionService repossessionService;

    /**
     * INST-5a — repossession writes off money and takes goods from a customer, which puts it in the same class
     * as a void or a delete. It is gated the way those are, and the expression is copied from
     * {@code DocumentTemplateController} rather than invented so there is one shape of owner-gate in this
     * service, not two.
     */
    private static final String OWNER_ONLY =
            "hasAuthority('ROLE_OWNER') or hasAuthority('SUPER_PRIVILEGE') or hasAuthority('ADMIN_PRIVILEGE')";
    @Autowired private RequestUtil requestUtil;
    @Autowired private com.myplus.business_service.repository.CustomerRepo customerRepo;
    /** ONB-3 — read directly for the two aggregates the migration preview needs; no service logic involved. */
    @Autowired private com.myplus.business_service.repository.InstallmentPlanRepo installmentPlanRepo;
    /** R4 — the people standing behind a financed sale. */
    @Autowired private com.myplus.business_service.service.PlanGuarantorService planGuarantorService;

    /**
     * Customer names for a set of plans, in ONE query.
     *
     * <p>Never one lookup per plan: a worklist of 200 plans would issue 200 queries, which is the O(n^2)
     * shape {@code addCustomer}'s in-memory duplicate scan already has and that this codebase has now paid
     * for twice.
     */
    private java.util.Map<Long, String> namesFor(List<InstallmentPlan> plans) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (InstallmentPlan p : plans) if (p.getCustomerId() != null) ids.add(p.getCustomerId());

        java.util.Map<Long, String> names = new java.util.HashMap<>();
        if (ids.isEmpty()) return names;
        for (com.myplus.business_service.entity.Customer c : customerRepo.findAllById(ids)) {
            names.put(c.getCustomerId(), c.getName());
        }
        return names;
    }

    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    /**
     * A customer's plans, newest first — the schedule block on the customer screen.
     *
     * <p>The customer id arrives <b>from the query string</b>, so the read is org-scoped in the repository
     * and the scope is the caller's own. That is the anti-IDOR rule the D2 credit-standing leak established:
     * <b>whether a read needs scoping depends on where the id came from, not on which method reads it.</b> An
     * id followed from a row the caller could already see is safe; an id off the wire is not.
     */
    /**
     * ONB-3 — what switching this tenant away from selling on terms would leave behind.
     *
     * <h3>⚠ {@code organizationId} is honoured ONLY for a platform operator</h3>
     * This reports a tenant's RECEIVABLES, so a parameter anyone could set would be a cross-tenant read of a
     * competitor's debtor book. It is therefore resolved through
     * {@link com.myplus.common.security.CurrentUser#organizationIdFor(Long)}: a {@code ROLE_ADMIN} operator
     * gets the org they asked about, everybody else silently gets their own however they spell the URL.
     *
     * <h3>Why the parameter has to exist at all</h3>
     * The operator reaches this through the monolith BFF, which is {@code ROLE_ADMIN}-gated — but the BFF
     * calls downstream with the OPERATOR's own credentials. Reading the token's org alone would answer the
     * confirmation dialog with the operator's figures while labelling them as the tenant's: not an error, a
     * wrong number, which is worse. The tenant's own Configuration screen passes nothing and reaches it as
     * itself.
     *
     * <p>Not capability-gated, and that is deliberate for the same reason the dashboard figure is not
     * (ONB-2 §5): a capability governs what a tenant may do next, never what they may see about what they
     * have already done. This endpoint exists precisely to be called while the capability is being taken
     * away.
     */
    @GetMapping("/installmentImpact")
    public GenericResponse installmentImpact(
            @RequestParam(name = "organizationId", required = false) Long organizationId) {
        Long orgId = com.myplus.common.security.CurrentUser.organizationIdFor(organizationId);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        long open = installmentPlanRepo.countOpenForOrg(orgId);
        out.put("openPlans", open);
        java.math.BigDecimal owed = installmentPlanRepo.sumOutstandingForOrg(orgId);
        // Coalesced HERE rather than in the query: "no plans" and "nothing left to pay" are different answers
        // to an operator, and only the caller knows it wants a number rather than the distinction.
        out.put("outstanding", owed == null ? java.math.BigDecimal.ZERO : owed);
        return new GenericResponse("SUCCESS", "impact", out);
    }

    @GetMapping("/installmentPlans")
    @ResponseBody
    public GenericResponse plansForCustomer(@RequestParam("customerId") Long customerId) {
        LocalDate today = LocalDate.now();
        List<InstallmentPlan> plans = installmentPlanService.plansForCustomer(orgId(), customerId);
        java.util.Map<Long, String> names = namesFor(plans);
        List<InstallmentPlanViewDTO> out = new ArrayList<>();
        for (InstallmentPlan p : plans) {
            out.add(InstallmentPlanViewDTO.of(p, today, names.get(p.getCustomerId())));
        }
        // Lands in `collection`, not `object`: GenericResponse overloads on Collection vs Object, and a
        // List binds the Collection one. Verified against the live endpoint rather than inferred — the
        // response is {"status":"SUCCESS","collection":[...]}, which is what every list client here expects.
        return new GenericResponse("SUCCESS", "OK", out);
    }

    /**
     * INST-1 — the schedule a customer would owe, WITHOUT committing anything.
     *
     * <p>The counter shows this before the sale so the cashier can read the dates and amounts out loud. It
     * runs the <b>same</b> {@code ScheduleGenerator} the commit runs, from the same {@code PlanTerms} — so
     * what the customer agrees to and what is stored cannot differ. A preview computed a second way is a
     * promise the system might not keep.
     *
     * <p>Writes nothing and takes no lock. Refusals come back as a readable message rather than a stack
     * trace, because the cashier is the person who has to act on them.
     */
    @GetMapping("/installmentPreview")
    @ResponseBody
    public GenericResponse preview(@RequestParam("cashPrice") java.math.BigDecimal cashPrice,
                                   @RequestParam(value = "downPayment", required = false) java.math.BigDecimal downPayment,
                                   @RequestParam("installmentCount") Integer installmentCount,
                                   @RequestParam(value = "frequency", required = false) String frequency,
                                   @RequestParam("firstDueDate") String firstDueDate) {
        try {
            com.myplus.common.installment.PlanTerms terms = new com.myplus.common.installment.PlanTerms(
                    cashPrice, downPayment, installmentCount == null ? 0 : installmentCount,
                    com.myplus.common.installment.Frequency.fromSetting(frequency),
                    LocalDate.parse(firstDueDate), null);

            String invalid = terms.validate();
            if (invalid != null) return new GenericResponse("FAILED", invalid);

            List<java.util.Map<String, Object>> rows = new ArrayList<>();
            for (com.myplus.common.installment.ScheduledAmount a
                    : com.myplus.common.installment.ScheduleGenerator.generate(terms)) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("seqNo", a.seqNo());
                row.put("dueDate", a.dueDate().toString());
                row.put("amount", a.amount());
                rows.add(row);
            }
            return new GenericResponse("SUCCESS",
                    terms.financedAmount().toPlainString(), rows);

        } catch (IllegalArgumentException e) {
            return new GenericResponse("FAILED", e.getMessage());
        } catch (Exception e) {
            return new GenericResponse("FAILED", "Those terms could not be used to build a schedule.");
        }
    }

    /**
     * Every plan in the tenant that still owes money, most overdue first — the Installments screen and the
     * collections worklist.
     */
    @GetMapping("/installmentPlansOpen")
    @ResponseBody
    public GenericResponse openPlans() {
        LocalDate today = LocalDate.now();
        List<InstallmentPlan> plans = installmentPlanService.openPlans(orgId(), today);
        java.util.Map<Long, String> names = namesFor(plans);
        List<InstallmentPlanViewDTO> out = new ArrayList<>();
        for (InstallmentPlan p : plans) {
            out.add(InstallmentPlanViewDTO.of(p, today, names.get(p.getCustomerId())));
        }
        // Lands in `collection`, not `object`: GenericResponse overloads on Collection vs Object, and a
        // List binds the Collection one. Verified against the live endpoint rather than inferred — the
        // response is {"status":"SUCCESS","collection":[...]}, which is what every list client here expects.
        return new GenericResponse("SUCCESS", "OK", out);
    }

    // ── INST-3a: the collections worklist ─────────────────────────────────────────────────────────────────

    /**
     * INST-3a — who to ring today.
     *
     * <p><b>Org-scoped in the repository, with no id off the wire at all.</b> {@link ReminderScanner} holds a
     * cross-tenant licence because a {@code @Scheduled} thread has no user to ask; that licence stops at the
     * scanner, and this is the boundary. A worklist that leaked would hand one shop its competitor's debtor
     * list, which is the assertion the gate leads with.
     *
     * @param stage optional filter — {@code DUE_SOON} or {@code OVERDUE}; absent means both
     */
    @GetMapping("/installmentReminders")
    @ResponseBody
    public GenericResponse reminders(@RequestParam(value = "stage", required = false) String stage) {
        return new GenericResponse("SUCCESS", "OK", reminderService.worklist(orgId(), stage));
    }

    /**
     * INST-3a — record that the customer was actually rung, and what they said.
     *
     * <p>The half that makes the worklist a collections tool rather than a list. Without it the shop rings the
     * same customer three times and never rings another, which is the whole reason a derived "who is overdue"
     * query was not enough on its own.
     *
     * <p>A refusal here is deliberately indistinguishable between "no such reminder" and "not yours" — a
     * distinguishable second answer confirms the row exists to somebody who should not know that.
     */
    @PostMapping("/installmentReminderAction")
    @ResponseBody
    public GenericResponse recordAction(@RequestParam("id") Long id,
                                        @RequestParam(value = "outcome", required = false) String outcome,
                                        @RequestParam(value = "note", required = false) String note) {
        boolean ok = reminderService.recordAction(orgId(), id, outcome, note);
        return ok ? new GenericResponse("SUCCESS", "Recorded")
                  : new GenericResponse("FAILED", "That reminder could not be updated.");
    }

    /**
     * INST-3a — refresh the worklist now instead of waiting for the timer.
     *
     * <p>Scoped to the caller's own tenant. The scheduler sweeps every tenant because it has no user; a
     * request has one, so there is no reason for this path to hold the wider licence.
     *
     * <p>Idempotent by construction — {@code installment_reminder.dedupe_key} is UNIQUE, so a shopkeeper who
     * clicks it five times gets the same list, not five copies of it.
     */
    @PostMapping("/scanInstallmentReminders")
    @ResponseBody
    public GenericResponse scanNow() {
        int recorded = reminderService.scanNow(orgId());
        return new GenericResponse("SUCCESS", String.valueOf(recorded));
    }

    // ── INST-5a: repossession ─────────────────────────────────────────────────────────────────────────────

    /**
     * INST-5a — take the financed item back and close the plan.
     *
     * <p>The unpaid balance is credited off through the existing {@code SALE_RETURN} path, the unit goes back
     * into stock, and the plan is cancelled — which frees its serial automatically, because
     * {@code live_asset_ref} is generated from the plan's status.
     *
     * <p><b>Money already paid is kept.</b> That is the customer's chosen treatment (design §7) and it is why
     * only the balance is credited: after this, {@code paidAmount == grandTotal}, so there is no overpayment
     * for anything to refund.
     *
     * @param condition {@code GOOD} restocks the unit; anything else records the repossession without putting
     *                  a damaged handset back on the shelf. A parameter rather than a setting — it is a fact
     *                  about this one repossession, not a policy of the shop.
     */
    @PostMapping("/repossessPlan")
    @ResponseBody
    @PreAuthorize(OWNER_ONLY)
    public GenericResponse repossess(@RequestParam("planId") Long planId,
                                     @RequestParam(value = "condition", required = false) String condition,
                                     @RequestParam(value = "reason", required = false) String reason) {
        try {
            com.myplus.business_service.service.RepossessionService.Outcome out =
                    repossessionService.repossess(orgId(), planId, condition, reason);
            return out.ok() ? new GenericResponse("SUCCESS", out.message(), out.creditNoteNo())
                            : new GenericResponse("FAILED", out.message());
        } catch (com.myplus.business_service.service.PeriodClosedException pce) {
            // Nothing was written before the guard — surface the reason rather than a generic rollback message.
            return new GenericResponse("FAILED", pce.getMessage());
        } catch (Exception e) {
            LOGGER.error("repossessPlan failed", e);
            return new GenericResponse("FAILED", "The repossession could not be completed.");
        }
    }

    // ── R4: guarantors ──────────────────────────────────────────────────────────────────────────

    /**
     * R4 — one plan's guarantors.
     *
     * <p>⚠ Scoped by the org from the TOKEN and not only by {@code planId}: the id arrives off the wire, and
     * an id off the wire is not an id followed from a row the caller could already see. A plan belonging to
     * another tenant answers with an empty list rather than a refusal, so a prober learns nothing — not even
     * whether the plan exists.
     */
    @GetMapping("/planGuarantors")
    public GenericResponse planGuarantors(@RequestParam("planId") Long planId) {
        Long orgId = com.myplus.common.security.CurrentUser.organizationId();
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        for (com.myplus.business_service.entity.PlanGuarantor g : planGuarantorService.forPlan(orgId, planId)) {
            rows.add(planGuarantorService.asMap(g));
        }
        return new GenericResponse("SUCCESS", "guarantors", rows);
    }

    /**
     * R4 — add a guarantor to a plan that already exists.
     *
     * <h3>Why a plan can gain one afterwards</h3>
     * 211 live plans carry none, because there was nowhere to record one. A feature that only ever applied to
     * future sales would leave every one of them permanently unguaranteed — the same reason there is no
     * backfill and no retrospective refusal.
     */
    @PostMapping("/savePlanGuarantor")
    public GenericResponse savePlanGuarantor(
            @RequestParam("planId") Long planId,
            @RequestParam("name") String name,
            @RequestParam(name = "cnic", required = false) String cnic,
            @RequestParam(name = "contact", required = false) String contact,
            @RequestParam(name = "address", required = false) String address,
            @RequestParam(name = "role", required = false) String role,
            @RequestParam(name = "customerId", required = false) Long customerId) {

        Long orgId = com.myplus.common.security.CurrentUser.organizationId();
        Long userId = com.myplus.common.security.CurrentUser.userId();

        // The plan must be THIS tenant's. Checked by reading it through the org-scoped path rather than
        // trusting the id.
        com.myplus.business_service.entity.InstallmentPlan plan =
                installmentPlanRepo.findById(planId).orElse(null);
        if (plan == null || orgId == null || !orgId.equals(plan.getOrganizationId())) {
            return new GenericResponse("FAILED", "No such plan.");
        }
        if (name == null || name.trim().isEmpty()) {
            return new GenericResponse("FAILED", "A guarantor needs a name.");
        }

        com.myplus.business_service.dto.GuarantorDTO g = new com.myplus.business_service.dto.GuarantorDTO();
        g.setName(name);
        g.setCnic(cnic);
        g.setContact(contact);
        g.setAddress(address);
        g.setRole(role);
        g.setCustomerId(customerId);

        planGuarantorService.save(orgId, planId, userId, java.util.List.of(g));
        return new GenericResponse("SUCCESS", "Guarantor added.");
    }

    /**
     * R4 — remove a guarantor.
     *
     * <p>{@code ADMIN_PRIVILEGE}, matching this service's rule for destructive operations: a guarantor record
     * is the shop's recourse, and removing one is not a cashier's decision.
     */
    @PostMapping("/deletePlanGuarantor")
    @PreAuthorize(OWNER_ONLY)
    public GenericResponse deletePlanGuarantor(@RequestParam("id") Long id) {
        Long orgId = com.myplus.common.security.CurrentUser.organizationId();
        boolean gone = planGuarantorService.delete(orgId, id);
        return gone ? new GenericResponse("SUCCESS", "Guarantor removed.")
                    : new GenericResponse("FAILED", "No such guarantor.");
    }

    /**
     * R4 — recall a guarantor this shop has used before, by their COMPLETE CNIC.
     *
     * <p>⚠ Exact match on 13 digits, within the caller's own org. A prefix search would let staff type
     * {@code 352} and walk a list of national identifiers; a full match cannot be walked, because the caller
     * already has to be holding the card. A short or unknown CNIC returns an empty object, never an error —
     * "not found" and "too short" look identical from outside, deliberately.
     */
    @GetMapping("/guarantorRecall")
    public GenericResponse guarantorRecall(@RequestParam(name = "cnic", required = false) String cnic) {
        Long orgId = com.myplus.common.security.CurrentUser.organizationId();
        return new GenericResponse("SUCCESS", "recall", planGuarantorService.recall(orgId, cnic));
    }

    /** R4 — the guarantors this shop uses most, for one-tap recall. This org's own, and nobody else's. */
    @GetMapping("/recentGuarantors")
    public GenericResponse recentGuarantors(
            @RequestParam(name = "limit", required = false, defaultValue = "8") int limit) {
        Long orgId = com.myplus.common.security.CurrentUser.organizationId();
        return new GenericResponse("SUCCESS", "recent",
                planGuarantorService.recent(orgId, Math.min(Math.max(limit, 1), 25)));
    }

    /** R4 — how many guarantors this shop requires, so the screen can render that many blocks. */
    @GetMapping("/guarantorsRequired")
    public GenericResponse guarantorsRequired() {
        Long orgId = com.myplus.common.security.CurrentUser.organizationId();
        return new GenericResponse("SUCCESS", "required",
                java.util.Map.of("required", planGuarantorService.requiredCount(orgId)));
    }
}
