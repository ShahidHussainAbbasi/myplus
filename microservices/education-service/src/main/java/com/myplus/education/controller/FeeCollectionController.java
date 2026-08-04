package com.myplus.education.controller;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.dto.FeeCollectionDTO;
import com.myplus.education.entity.FeeCollection;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.FeeCollectionRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.education.service.FeeValidator;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;
import com.myplus.education.util.ScopedDeleter;

/**
 * Flat (legacy) Fee Collection endpoints — core list/add/delete. userId-scoped.
 * NOTE: loadFV (fee voucher), loadFL/loadFR (ledger/receipt) and findFc are deferred to a focused
 * follow-up (voucher computation + student/grade joins).
 */
@Controller
public class FeeCollectionController {

    @Autowired
    private FeeCollectionRepository feeCollectionRepository;
    @Autowired
    private StudentRepository studentRepository;   // P4: resolve visible students' enrollNos for branch scoping
    @Autowired
    private com.myplus.education.service.FeeService feeService;   // reads the org's fee-branch-scope policy
    @Autowired
    private RequestUtil requestUtil;

    @Autowired
    private ScopedDeleter scopedDeleter;   // anti-IDOR bulk delete
    @Autowired
    private AppUtil appUtil;

    @Autowired
    private com.myplus.education.service.TermService termService;   // slice 1.1 — current-term stamping
    @Autowired
    private com.myplus.education.service.GlOutboxService glOutboxService;   // slice 0.1: fee revenue → GL
    @Autowired
    private com.myplus.education.service.FeeArrearsService feeArrearsService;   // slice 0.2a: AR / aging / statement
    @Autowired(required = false)
    private com.myplus.common.credit.CreditService creditService;   // slice 0.2b: fee credit (shared rules)
    @Autowired
    private com.myplus.common.settings.SettingsService settingsService;   // edu.fee.creditOnOverpayment
    
    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private FeeCollectionDTO toDto(FeeCollection o) {
        FeeCollectionDTO dto = new FeeCollectionDTO();
        dto.setId(o.getId());
        dto.setUserId(o.getUserId());
        dto.setEnrollNo(o.getEnrollNo());
        dto.setDiscountType(o.getDiscountType());
        dto.setDiscount(o.getDiscount());
        dto.setDueDayOfMonth(o.getDueDayOfMonth());
        dto.setDueAmount(o.getDueAmount());
        dto.setFee(o.getFee());
        dto.setFeePaid(o.getFeePaid());
        dto.setOtherDues(o.getOtherDues());
        dto.setOtherDuesDescription(o.getOtherDuesDescription());
        dto.setPayee(o.getPayee());
        dto.setReceivedBy(o.getReceivedBy());
        dto.setReceivedIn(o.getReceivedIn());
        // Same gap on the way out: these three were never returned, so the UI could not show a balance and the
        // AR reads had nothing to work with.
        dto.setDueBalance(o.getDueBalance());
        dto.setVehicleFee(o.getVehicleFee());
        dto.setCheckNo(o.getCheckNo());
        dto.setPdStr(appUtil.getLocalDateStr(o.getPaymentDate()));
        return dto;
    }

    /**
     * Fee-collection visibility. By org policy (FeeSetting.feeCollectionBranchScoped, default FALSE) a fee can
     * be viewed/collected from ANY branch — a guardian may pay at any campus — so the default is org-wide. Only
     * when the owner opts INTO branch scoping does a fee become visible solely to the student's branch (a fee
     * is for a student, resolved by enrollNo). Owner/super always see org-wide.
     */
    private List<FeeCollection> branchVisible(List<FeeCollection> rows) {
        if (!Boolean.TRUE.equals(feeService.settingFor(orgId(), userId()).getFeeCollectionBranchScoped()))
            return rows;   // org-wide (the default): fees are collectible at any branch
        if (requestUtil.isOwnerSuper()) return rows;
        java.util.Set<Long> schools = requestUtil.accessibleSchoolIds();
        if (schools.isEmpty()) return rows;
        java.util.Set<String> visibleEn = studentRepository.findScopedBySchools(orgId(), schools).stream()
                .map(Student::getEnrollNo).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return rows.stream().filter(f -> f.getEnrollNo() == null || visibleEn.contains(f.getEnrollNo()))
                .collect(Collectors.toList());
    }

    @RequestMapping(value = "/getUserFc", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserFc(final HttpServletRequest request) {
        try {
            List<FeeCollection> objs = branchVisible(feeCollectionRepository.findScoped(orgId(), userId()));
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getAllFc", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllFc(final HttpServletRequest request) {
        try {
            // Tenant- AND branch-scoped: a branch-constrained caller sees only their branches' fee records.
            List<FeeCollection> all = branchVisible(feeCollectionRepository.findScoped(orgId(), userId()));
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Slice 0.2a: a fee CHARGE raises a receivable — Dr AR = Cr Fee Income.
     *
     * Accrual, matching POS/Pharma: revenue is recognised when the fee is charged, not when it is collected, so a
     * school's unpaid fees show on the balance sheet. Slice 0.1's FEE_COLLECTION event was retired — the payment
     * side now goes through the shared subledger/RECEIPT path instead (see settleFeePayment).
     *
     * Best-effort by contract, and safe because the enqueue is transactional: an undelivered event is retried by
     * the relay, never lost.
     */
    private void enqueueFeeCharge(FeeCollection fc) {
        try {
            java.math.BigDecimal charged = fc.getDueAmount() == null
                    ? java.math.BigDecimal.ZERO : java.math.BigDecimal.valueOf(fc.getDueAmount());
            if (charged.signum() <= 0) return;   // nothing charged → not an accounting event
            glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
                    .eventType("FEE_CHARGE")
                    .date(java.time.LocalDate.now())
                    .ref(String.valueOf(fc.getId()))       // no invoice number in education yet — the fee id traces it
                    .grandTotal(charged)
                    .build());
        } catch (Exception e) {
            appUtil.le(getClass(), e);   // the fee is already saved; never surface a GL problem to the clerk
        }
    }

    /**
     * Slice 0.2a: fee aging across the org — 0–30 / 31–60 / 61–90 / 90+ per student, from the SHARED
     * AgingCalculator. Replaces the arrears screen's own arithmetic with the engine POS/AP already use.
     */
    @RequestMapping(value = "/getFeeAging", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getFeeAging() {
        try {
            return new GenericResponse("SUCCESS", "",
                    feeArrearsService.aging(orgId(), userId(), java.time.LocalDate.now()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Slice 0.2a: one student's statement of account — charges, payments and a running balance, built by the
     * SHARED StatementBuilder. This is the document a guardian actually asks for.
     */
    @RequestMapping(value = "/getFeeStatement", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getFeeStatement(final HttpServletRequest request) {
        try {
            String enrollNo = request.getParameter("enrollNo");
            if (appUtil.isEmptyOrNull(enrollNo)) return new GenericResponse("FAILED", "enrollNo is required");
            return new GenericResponse("SUCCESS", "", feeArrearsService.statement(orgId(), enrollNo));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }


    /** Slice 0.2b: post one of the credit legs to the GL through the same outbox the fee charge uses. */
    private void enqueueCreditGl(String eventType, java.math.BigDecimal amount, FeeCollection fc, String method) {
        if (amount == null || amount.signum() <= 0) return;
        try {
            glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
                    .eventType(eventType)
                    .date(java.time.LocalDate.now())
                    .ref(String.valueOf(fc.getId()))
                    .grandTotal(amount)
                    .method(method)
                    .build());
        } catch (Exception e) {
            appUtil.le(getClass(), e);   // the money movement is already recorded; the outbox retries the journal
        }
    }

    /** Owner policy: carry an overpayment forward as fee credit (default ON). Off ⇒ the 0.2a refusal. */
    private boolean creditEnabled() {
        try { return settingsService.getBool("edu.fee.creditOnOverpayment"); }
        catch (Exception e) { return true; }   // fail SAFE for the guardian: keep their money rather than reject it
    }

    /**
     * Slice 0.2b: apply any credit the student holds to what is still owed, then carry an unallocated surplus
     * forward as new credit.
     *
     * Order matters. Credit is spent FIRST — a guardian should never be asked for money the school is already
     * holding for them — and only what remains unmatched becomes new credit.
     *
     * @return a human summary of what happened, or null when credit played no part
     */
    private String applyCredit(Long orgId, FeeCollection fc, Student student, int tendered, int owedBeforeTender) {
        if (creditService == null || !creditEnabled() || student == null) return null;
        StringBuilder note = new StringBuilder();

        // 1. Carry forward whatever was tendered beyond what was actually owed. `owedBeforeTender` is the
        //    capacity measured BEFORE settlement (existing dues + this row's charge), so the surplus is exact.
        //    GL: Dr Cash = Cr 2200 — the school received money it does not own yet, which is a liability.
        int surplus = Math.max(tendered - owedBeforeTender, 0);
        if (surplus > 0) {
            java.math.BigDecimal amt = java.math.BigDecimal.valueOf(surplus);
            creditService.issue(student.getId(), amt, "OVERPAYMENT", String.valueOf(fc.getId()));
            enqueueCreditGl("FEE_CREDIT_ISSUED", amt, fc, glMethod(fc.getReceivedIn()));
            note.append("Carried ").append(surplus).append(" forward as fee credit. ");
        }

        // 2. Spend existing credit on anything STILL outstanding. Credit is spent before the guardian is asked for
        //    more — never ask for money the school is already holding for them.
        //    GL: Dr 2200 = Cr AR — the liability shrinks and the receivable clears. NO cash leg: this is not a
        //    receipt, and posting it as one would count the same money as received twice.
        java.math.BigDecimal owing = feeArrearsService.totalOutstanding(orgId, fc.getEnrollNo());
        if (owing.signum() > 0) {
            java.math.BigDecimal used = creditService.redeem(student.getId(), owing, String.valueOf(fc.getId()));
            if (used.signum() > 0) {
                java.math.BigDecimal absorbed =
                        feeArrearsService.applyCreditToDues(orgId, fc.getEnrollNo(), used);
                enqueueCreditGl("FEE_CREDIT_APPLIED", absorbed, fc, null);
                note.append("Applied ").append(absorbed).append(" from fee credit.");
            }
        }
        return note.length() == 0 ? null : note.toString().trim();
    }

    /**
     * Slice 0.2a: refuse an overpayment BEFORE the row is saved, so a refusal never leaves money half-applied.
     *
     * A guardian may pay at most what is owed: the dues already outstanding PLUS the charge this row raises. Paying
     * more is refused rather than silently driving a balance negative — fee credit (carrying the surplus to next
     * month) is slice 0.2b, and until it exists an honest error beats a wrong number.
     *
     * @return a message explaining the refusal, or null when the payment is acceptable
     */
    private String checkOverpayment(Long orgId, FeeCollection fc, int paid) {
        if (paid <= 0) return null;
        // Slice 0.2b: with fee credit enabled (the default) a surplus is CARRIED FORWARD rather than refused, so
        // there is nothing to reject here. The refusal path below survives for schools that switch the policy
        // off — some genuinely will not hold guardian money.
        if (creditEnabled() && creditService != null) return null;
        int charge = fc.getDueAmount() == null ? 0 : fc.getDueAmount();
        // totalOutstanding excludes this row — it is not persisted yet on a create.
        int capacity = feeArrearsService.totalOutstanding(orgId, fc.getEnrollNo()).intValue() + charge;
        if (paid > capacity) {
            return "Payment " + paid + " exceeds the total owed " + capacity
                    + ". Fee credit (carry forward) is not enabled yet — collect the outstanding amount only.";
        }
        return null;
    }

    /**
     * Slice 0.2a: settle the amount paid across the student's outstanding dues, OLDEST FIRST, through the SHARED
     * subledger — the identical path a POS customer receipt takes, so finance sees one kind of receipt.
     *
     * Best-effort: the collection is already recorded, so a ledger hiccup is reconciled later rather than failing
     * a payment the guardian has made.
     */
    private String settleFeePayment(Long orgId, FeeCollection fc, int tendered) {
        java.math.BigDecimal paid = java.math.BigDecimal.valueOf(tendered);
        if (paid.signum() <= 0) return null;   // nothing received → nothing to settle

        // The student IS the party the ledger records the receipt against, so a fee whose enrolment number
        // resolves to nobody cannot be settled. Say so instead of silently doing nothing: the money would
        // otherwise appear collected while every due stayed open, and the books would drift with no signal.
        Student student = studentRepository.findByOrganizationIdAndEnrollNo(orgId, fc.getEnrollNo()).orElse(null);
        if (student == null) {
            return "No student found for enrolment number '" + fc.getEnrollNo()
                    + "'. Register the student before collecting fees.";
        }

        try {
            feeArrearsService.settle(orgId, fc.getEnrollNo(), student.getName(), student.getId(),
                    paid, glMethod(fc.getReceivedIn()), fc.getPaymentDate(), String.valueOf(fc.getId()));
            return null;
        } catch (Exception e) {
            // Best-effort ONLY for a downstream ledger hiccup — the local allocation has already been applied and
            // the collection is recorded, so this reconciles later rather than failing a guardian's payment.
            appUtil.le(getClass(), e);
            return null;
        }
    }

    /**
     * Map the school's "Received In" value onto finance's method vocabulary.
     *
     * Necessary, not cosmetic: finance routes to the Bank account with {@code startsWith("CHEQUE")}, and this
     * field stores {@code "Check"} — passing it through verbatim would silently post every cheque to Cash.
     */
    private String glMethod(String receivedIn) {
        if (receivedIn == null) return "CASH";
        return receivedIn.trim().toUpperCase().startsWith("CHE") ? "CHEQUE" : "CASH";
    }

    // D-3 privilege map: money / structure / policy — not routine data entry
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @RequestMapping(value = "/addFc", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addFc(final FeeCollectionDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();

            // Slice B (D1): validate BEFORE anything is written or posted. A negative feePaid would otherwise
            // reach settleFeePayment → the shared subledger → a GL receipt, posting a negative cash receipt
            // into a ledger three other verticals share — far more expensive to unpick there than to refuse here.
            java.util.List<String> problems = FeeValidator.validate(dto);
            if (!problems.isEmpty()) {
                // Every problem at once: a clerk fixing one field per round trip is how a form earns its
                // reputation.
                return new GenericResponse("FAILED", String.join("; ", problems));
            }

            // Anti-IDOR: an edit names a fee record by a client-supplied id, so it must be resolved WITHIN
            // the caller's tenant. A bare findById followed by the setOrganizationId below would have moved
            // another school's PAYMENT RECORD into this one — the money row leaves its owner's books.
            FeeCollection obj;
            if (dto.getId() != null) {
                obj = feeCollectionRepository.findByIdScoped(dto.getId(), orgId, userId).orElse(null);
                if (obj == null) return new GenericResponse("NOT_FOUND", "Fee record not found");
            } else {
                obj = new FeeCollection();
            }
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setEnrollNo(dto.getEnrollNo());
            obj.setDiscountType(dto.getDiscountType());
            obj.setDiscount(dto.getDiscount());
            obj.setDueDayOfMonth(dto.getDueDayOfMonth());
            obj.setDueAmount(dto.getDueAmount());
            obj.setFee(dto.getFee());
            obj.setOtherDues(dto.getOtherDues());
            obj.setOtherDuesDescription(dto.getOtherDuesDescription());
            obj.setPayee(dto.getPayee());
            obj.setReceivedBy(dto.getReceivedBy());
            obj.setReceivedIn(dto.getReceivedIn());
            // Pre-existing gap found by slice 0.2a: the form collects V. Fee and Check No and the entity has
            // columns for them, but addFc never persisted either — they were silently dropped on every save.
            obj.setVehicleFee(dto.getVehicleFee());
            obj.setCheckNo(dto.getCheckNo());
            final int tendered = dto.getFeePaid() == null ? 0 : dto.getFeePaid();
            if (!appUtil.isEmptyOrNull(dto.getPdStr())) {
                obj.setPaymentDate(appUtil.getLocalDate(dto.getPdStr()));
            }
            final boolean isNew = (dto.getId() == null);

            // dueBalance and feePaid are DERIVED and owned by the SUBLEDGER — never client-supplied. Money the
            // server relies on must not come from the browser (the rule the sell flow learned about line totals),
            // and these two are what make a row an open receivable.
            //
            // ONLY on create. A new row opens at its FULL charge with nothing paid; the tendered amount is then
            // handed to the subledger, which decides WHICH rows it settles — a guardian paying this month may owe
            // older months, and FIFO must reach the oldest first. Applying the tender here as well would count the
            // same payment twice.
            //
            // On an EDIT these are deliberately left alone: they now hold settlement history, and overwriting them
            // would silently erase which dues had already been paid.
            if (isNew) {
                obj.setDueBalance(dto.getDueAmount() == null ? 0 : dto.getDueAmount());
                obj.setFeePaid(0);
            }

            // Slice 0.2a: refuse an overpayment BEFORE saving, so the refusal leaves no half-applied money behind.
            // Checked against what is outstanding EXCLUDING this row (it is not persisted yet on a create).
            // Capacity BEFORE anything is settled — existing dues plus this row's charge. Captured here because
            // once settlement runs the outstanding figure has already moved, and the surplus would be unknowable.
            final int owedBeforeTender = isNew
                    ? feeArrearsService.totalOutstanding(orgId, obj.getEnrollNo()).intValue()
                        + (dto.getDueAmount() == null ? 0 : dto.getDueAmount())
                    : 0;

            if (isNew) {
                String refusal = checkOverpayment(orgId, obj, tendered);
                // Slice B (B2/D3): ANY charging row needs a real student, not just a tendered payment.
                // 0.2a checked only `tendered > 0`, which was right for protecting money already handed over —
                // but a row with dueAmount > 0 and feePaid = 0 then sat in arrears and aging FOREVER against a
                // student nobody could find: a permanent, uncollectable debit no screen could explain.
                // Deliberately CREATE-only: an edit of an existing row whose student was since removed must stay
                // correctable, or the only way to fix a bad row would be to recreate the student.
                if (refusal == null && FeeValidator.isChargingRow(dto)
                        && studentRepository.findByOrganizationIdAndEnrollNo(orgId, obj.getEnrollNo()).isEmpty()) {
                    refusal = "No student found for enrolment number '" + obj.getEnrollNo()
                            + "'. Register the student before recording fees or dues.";
                }
                if (refusal != null) return new GenericResponse("FAILED", refusal);
            }

            // Slice 1.1 (D4): stamp the term a NEW collection belongs to, so "Term 1 dues" can be asked
            // without re-deriving it from dates later. Never rewritten on edit — moving a historical
            // receipt into another term would silently restate a closed term's revenue. Null when the
            // school has not defined terms, which stays a permanently valid state.
            if (obj.getTermId() == null) obj.setTermId(termService.currentTermId(orgId, userId()));

            FeeCollection saved = feeCollectionRepository.save(obj);
            if (appUtil.isEmptyOrNull(saved)) return new GenericResponse("FAILED", "");

            // Only on CREATE: re-charging on every edit would double-count the receivable.
            String creditNote = null;
            if (isNew) {
                enqueueFeeCharge(saved);            // Dr AR = Cr Fee Income (the due is raised)
                // Only the part of the tender that actually settles dues is a cash RECEIPT (Dr Cash = Cr AR). Passing the
                // full tender would credit AR by more than was owed and drive it negative; the surplus is handled
                // as credit below (Dr Cash = Cr 2200).
                settleFeePayment(orgId, saved, Math.min(tendered, owedBeforeTender));
                // Slice 0.2b: carry any surplus forward, and spend existing credit on what is still owed.
                Student st = studentRepository.findByOrganizationIdAndEnrollNo(orgId, saved.getEnrollNo()).orElse(null);
                creditNote = applyCredit(orgId, saved, st, tendered, owedBeforeTender);
            }

            // Tell the clerk what happened to the money. Silently absorbing an overpayment is how a guardian ends
            // up unable to account for what they paid.
            return new GenericResponse("SUCCESS", creditNote == null ? "" : creditNote);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @RequestMapping(value = "/deleteFc", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteFc(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                // Anti-IDOR: only rows in the caller's own tenant are deleted (see ScopedDeleter).
                scopedDeleter.deleteScoped(feeCollectionRepository, ids,
                        FeeCollection::getOrganizationId, FeeCollection::getUserId, null);
                return true;
            }
            return false;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return false;
        }
    }
}
