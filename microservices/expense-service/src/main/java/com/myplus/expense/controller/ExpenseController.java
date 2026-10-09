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

    @GetMapping("/categories")
    public ApiResponse<List<CategoryView>> categories() {
        return ApiResponse.success(categories.list());
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

    @GetMapping("/vouchers/{id}/payments")
    public ApiResponse<List<com.myplus.expense.dto.ExpenseDtos.BillPaymentView>> payments(@PathVariable Long id) {
        return ApiResponse.success(bills.list(id));
    }

    @GetMapping("/vouchers")
    public ApiResponse<PageResponse<VoucherView>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(vouchers.list(from, to, status, page, size));
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
        return ApiResponse.success(vouchers.record(r, post, k), post ? "Expense saved — posting to the books" : "Draft saved");
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
