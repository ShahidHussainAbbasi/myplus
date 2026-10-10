package com.myplus.expense.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.expense.dto.ExpenseDtos.CategoryRequest;
import com.myplus.expense.dto.ExpenseDtos.CategoryView;
import com.myplus.expense.dto.ExpenseDtos.VoidRequest;
import com.myplus.expense.dto.ExpenseDtos.VoucherRequest;
import com.myplus.expense.dto.ExpenseDtos.VoucherView;
import com.myplus.expense.service.ExpenseCategoryService;
import com.myplus.expense.service.ExpenseVoucherService;

import lombok.RequiredArgsConstructor;

/**
 * EX-1 API, command style: state changes are their own endpoints ({@code /post}, {@code /void}), never a status
 * field in a PATCH, so each carries its own permission, validation and audit.
 *
 * <p>Refusals a person can act on are 400 {@code {success:false, message}} (ValidationException); another tenant's
 * or a colleague's voucher is 404 (no existence probe); a tier that may not act is 403.
 */
@RestController
@RequestMapping("/api/expense")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseCategoryService categories;
    private final ExpenseVoucherService vouchers;
    private final com.myplus.expense.service.ExpenseTagService tagService;
    private final com.myplus.expense.service.ExpenseBillService bills;
    private final com.myplus.expense.service.ExpenseClaimService claims;
    private final com.myplus.expense.service.ExpenseAdvanceService advances;   // EX-7b
    private final com.myplus.expense.service.ExpenseReportService reports;     // EX-8a
    private final com.myplus.expense.service.ExpenseSettings expenseSettings; // EX-6b — the limit, to say it

    @GetMapping("/categories")
    public ApiResponse<List<CategoryView>> categories() {
        return ApiResponse.success(categories.list());
    }

    /** EX-2e — the accounts the Categories screen offers (expense accounts only). */
    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @GetMapping("/categories/accounts")
    public ApiResponse<List<com.myplus.expense.dto.ExpenseDtos.ExpenseAccountView>> categoryAccounts() {
        return ApiResponse.success(categories.expenseAccounts());
    }

    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @PostMapping("/categories")
    public ApiResponse<CategoryView> createCategory(@RequestBody CategoryRequest r) {
        return ApiResponse.success(categories.create(r), "Category saved");
    }

    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @PatchMapping("/categories/{id}")
    public ApiResponse<CategoryView> updateCategory(@PathVariable Long id, @RequestBody CategoryRequest r) {
        return ApiResponse.success(categories.update(id, r), "Category saved");
    }

    /** EX-2b — what expenses can be tagged to on the asking dashboard (source = education | agriculture). */
    @GetMapping("/tags")
    public ApiResponse<List<com.myplus.commerce.contracts.dto.ExpenseTagView>> tags(@RequestParam(required = false) String source) {
        return ApiResponse.success(tagService.options(source));
    }

    /** FP-3 — who a bill can be owed to: the caller's suppliers, as business-service lists them. */
    @GetMapping("/suppliers")
    public ApiResponse<List<com.myplus.commerce.contracts.dto.ExpenseTagView>> suppliers() {
        return ApiResponse.success(tagService.suppliers());
    }

    /** FP-3 — pay (part of) a bill. Idempotency-Key header (or parameter, for the monolith's proxy) is required. */
    @PostMapping("/vouchers/{id}/pay")
    public ApiResponse<com.myplus.expense.dto.ExpenseDtos.BillPaymentView> pay(@PathVariable Long id,
            @RequestBody com.myplus.expense.dto.ExpenseDtos.PayRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestParam(value = "idempotencyKey", required = false) String keyParam) {
        String k = key != null && !key.isBlank() ? key : keyParam;
        return ApiResponse.success(bills.pay(id, r, k), "Payment recorded");
    }

    /** FP-3b — reverse one payment of a bill (owner/admin); the bill owes it again. Body: {"reason": "..."}. */
    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @PostMapping("/vouchers/{id}/payments/{paymentId}/reverse")
    public ApiResponse<com.myplus.expense.dto.ExpenseDtos.BillPaymentView> reversePayment(@PathVariable Long id,
            @PathVariable Long paymentId, @RequestBody(required = false) VoidRequest r) {
        return ApiResponse.success(bills.reversePayment(id, paymentId, r == null ? null : r.reason()), "Payment reversed");
    }

    @GetMapping("/vouchers/{id}/payments")
    public ApiResponse<List<com.myplus.expense.dto.ExpenseDtos.BillPaymentView>> payments(@PathVariable Long id) {
        return ApiResponse.success(bills.list(id));
    }

    @GetMapping("/vouchers")
    public ApiResponse<PageResponse<VoucherView>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String claim,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(vouchers.list(from, to, status, claim, page, size));
    }

    /** EX-2d — what the list's date filter adds up to (posted only), for the footer. */
    @GetMapping("/vouchers/totals")
    public ApiResponse<com.myplus.expense.dto.ExpenseDtos.VoucherTotals> totals(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(vouchers.totals(from, to));
    }

    @GetMapping("/vouchers/{id}")
    public ApiResponse<VoucherView> get(@PathVariable Long id) {
        return ApiResponse.success(vouchers.get(id));
    }

    @PostMapping("/vouchers")
    public ApiResponse<VoucherView> record(@RequestBody VoucherRequest r,
                                           @RequestParam(defaultValue = "true") boolean post,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestParam(value = "idempotencyKey", required = false) String keyParam) {
        // The header is the standard; the parameter exists because the monolith's GatewayClient forwards a body
        // and a query but no custom headers. Either way the same UNIQUE index carries the guarantee.
        String k = key != null && !key.isBlank() ? key : keyParam;
        VoucherView v = vouchers.record(r, post, k);
        if (post && "DRAFT".equals(v.status())) {
            // EX-6b — kept back: above what a member may post (the screen shows this sentence as it is)
            java.math.BigDecimal limit = expenseSettings.userPostLimit();
            return ApiResponse.success(v, "Saved. It is above the " + (limit == null ? "amount" : limit.toPlainString())
                    + " a member may post, so it waits for an owner or admin to post it.");
        }
        return ApiResponse.success(v, post ? "Expense saved — posting to the books" : "Draft saved");
    }

    /** EX-8b — the same payee, date and amount already recorded (numbers only): the screen asks before saving. */
    @GetMapping("/vouchers/duplicates")
    public ApiResponse<List<String>> possibleDuplicates(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date,
            @RequestParam(required = false) java.math.BigDecimal amount, @RequestParam(required = false) String payee) {
        return ApiResponse.success(vouchers.possibleDuplicates(date, amount, payee));
    }

    // ── EX-8a — the expense report (reconciles with the P&L) and its CSV ───────────────────────────────────────

    @GetMapping("/reports/summary")
    public ApiResponse<com.myplus.expense.service.ExpenseReportService.Summary> reportSummary(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(required = false) String by) {
        return ApiResponse.success(reports.summary(from, to, by));
    }

    @GetMapping(value = "/reports/expenses.csv", produces = "text/csv")
    public org.springframework.http.ResponseEntity<byte[]> reportCsv(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        byte[] body = reports.csv(from, to).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"expenses.csv\"")
                .header("Cache-Control", "private, no-store")
                .body(body);
    }

    // ── EX-7b — advances to staff (owner/admin give and take back; a member sees their own balance) ─────────────

    @GetMapping("/advances")
    public ApiResponse<List<com.myplus.expense.service.ExpenseAdvanceService.BalanceView>> advanceBalances() {
        return ApiResponse.success(advances.balances());
    }

    @GetMapping("/advances/staff")
    public ApiResponse<List<com.myplus.expense.service.StaffDirectory.Member>> advanceStaff() {
        return ApiResponse.success(advances.staff());
    }

    @GetMapping("/advances/{userId}/movements")
    public ApiResponse<List<com.myplus.expense.service.ExpenseAdvanceService.MovementView>> advanceMovements(@PathVariable Long userId) {
        return ApiResponse.success(advances.movements(userId));
    }

    @PostMapping("/advances/give")
    public ApiResponse<com.myplus.expense.service.ExpenseAdvanceService.MovementView> giveAdvance(
            @RequestBody com.myplus.expense.service.ExpenseAdvanceService.AdvanceRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestParam(value = "idempotencyKey", required = false) String keyParam) {
        return ApiResponse.success(advances.give(r, key != null && !key.isBlank() ? key : keyParam), "Advance given");
    }

    @PostMapping("/advances/take-back")
    public ApiResponse<com.myplus.expense.service.ExpenseAdvanceService.MovementView> takeBackAdvance(
            @RequestBody com.myplus.expense.service.ExpenseAdvanceService.AdvanceRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestParam(value = "idempotencyKey", required = false) String keyParam) {
        return ApiResponse.success(advances.takeBack(r, key != null && !key.isBlank() ? key : keyParam), "Advance taken back");
    }

    // ── EX-6 — claims ────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/claims")
    public ApiResponse<VoucherView> submitClaim(@RequestBody VoucherRequest r,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                @RequestParam(value = "idempotencyKey", required = false) String keyParam) {
        String k = key != null && !key.isBlank() ? key : keyParam;
        return ApiResponse.success(claims.submit(r, k), "Claim sent for approval");
    }

    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @PostMapping("/claims/{id}/approve")
    public ApiResponse<VoucherView> approveClaim(@PathVariable Long id) {
        return ApiResponse.success(claims.approve(id), "Claim approved — posting to the books");
    }

    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @PostMapping("/claims/{id}/reject")
    public ApiResponse<VoucherView> rejectClaim(@PathVariable Long id, @RequestBody(required = false) VoidRequest r) {
        return ApiResponse.success(claims.reject(id, r == null ? null : r.reason()), "Claim rejected");
    }

    @PostMapping("/claims/{id}/withdraw")
    public ApiResponse<VoucherView> withdrawClaim(@PathVariable Long id) {
        return ApiResponse.success(claims.withdraw(id), "Claim withdrawn");
    }

    @PostMapping("/vouchers/{id}/post")
    public ApiResponse<VoucherView> post(@PathVariable Long id) {
        return ApiResponse.success(vouchers.post(id), "Posting to the books");
    }

    /** EX-1b — send again what the books refused (after reopening a period, say). */
    @PostMapping("/vouchers/{id}/post-again")
    public ApiResponse<VoucherView> postAgain(@PathVariable Long id) {
        return ApiResponse.success(vouchers.postAgain(id), "Sent to the books again");
    }

    @PostMapping("/vouchers/{id}/void")
    public ApiResponse<VoucherView> voidVoucher(@PathVariable Long id, @RequestBody(required = false) VoidRequest r) {
        return ApiResponse.success(vouchers.voidVoucher(id, r == null ? null : r.reason()), "Expense voided");
    }

    @DeleteMapping("/vouchers/{id}")
    public ApiResponse<Void> deleteDraft(@PathVariable Long id) {
        vouchers.deleteDraft(id);
        return ApiResponse.success(null, "Draft deleted");
    }
}
