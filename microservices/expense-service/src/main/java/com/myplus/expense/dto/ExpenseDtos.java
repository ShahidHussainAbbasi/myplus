package com.myplus.expense.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.myplus.expense.entity.ExpenseCategory;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;

/**
 * The API's request and response shapes, kept together because each is small and they change together.
 * Entities never leave the service (anti-corruption at our own edge).
 */
public final class ExpenseDtos {

    private ExpenseDtos() { }

    public record CategoryRequest(String code, String name, String accountCode, Boolean active, Integer sortOrder) { }

    public record CategoryView(Long id, String code, String name, String accountCode, boolean active, int sortOrder) {
        public static CategoryView of(ExpenseCategory c) {
            return new CategoryView(c.getId(), c.getCode(), c.getName(), c.getAccountCode(),
                    Boolean.TRUE.equals(c.getActive()), c.getSortOrder() == null ? 0 : c.getSortOrder());
        }
    }

    public record LineRequest(Long categoryId, BigDecimal amount, String description, String tagType, Long tagId) { }

    /** FP-3 — {@code supplierId} and {@code dueDate} belong to a bill (paidFrom = AP) and are refused on anything else. */
    public record VoucherRequest(LocalDate voucherDate, String paidFrom, Long storeId, String payeeName, String note,
                                 List<LineRequest> lines, Long supplierId, LocalDate dueDate) { }

    /** FP-3 — pay (part of) a bill. method CASH | BANK; paidOn defaults to today. */
    public record PayRequest(BigDecimal amount, String method, LocalDate paidOn) { }

    public record BillPaymentView(Long id, Long voucherId, BigDecimal amount, String method, LocalDate paidOn,
                                  String status, String receiptNo, String lastError, LocalDateTime createdAt,
                                  String reference, boolean reversible, String reversalReceiptNo, String reversalReason,
                                  LocalDateTime reversedAt) {
        public static BillPaymentView of(com.myplus.expense.entity.ExpenseBillPayment p) {
            return new BillPaymentView(p.getId(), p.getVoucherId(), p.getAmount(), p.getMethod(), p.getPaidOn(),
                    p.getStatus(), p.getReceiptNo(), p.getLastError(), p.getCreatedAt(),
                    p.getReference(), p.reversible(), p.getReversalReceiptNo(), p.getReversalReason(), p.getReversedAt());
        }
    }

    public record VoidRequest(String reason) { }

    /** EX-2e — a ledger account a category may point at (an EXPENSE account; never 5000 Cost of Goods Sold). */
    public record ExpenseAccountView(String code, String name) { }

    /** EX-2d — the list's footer: how many posted expenses the filter holds and what they add up to. */
    public record VoucherTotals(long count, BigDecimal total) { }

    public record LineView(int lineNo, Long categoryId, String categoryName, String accountCode, String description,
                           BigDecimal amount, String tagType, Long tagId, String tagLabel) {
        public static LineView of(ExpenseVoucherLine l) {
            return new LineView(l.getLineNo(), l.getCategoryId(), l.getCategoryName(), l.getAccountCode(),
                    l.getDescription(), l.getAmount(), l.getTagType(), l.getTagId(), l.getTagLabel());
        }
    }

    public record VoucherView(Long id, String voucherNo, LocalDate voucherDate, String paidFrom, Long storeId,
                              String payeeName, String note, BigDecimal total, String status, String postingStatus,
                              String postingError, String voidReason, LocalDateTime voidedAt, Long userId,
                              Integer version, List<LineView> lines, String source, String sourceRef,
                              Long supplierId, String supplierName, LocalDate dueDate, BigDecimal paidAmount,
                              BigDecimal openAmount) {
        public static VoucherView of(ExpenseVoucher v) {
            return new VoucherView(v.getId(), v.getVoucherNo(), v.getVoucherDate(), v.getPaidFrom(), v.getStoreId(),
                    v.getPayeeName(), v.getNote(), v.getTotal(), v.getStatus(), v.getPostingStatus(),
                    v.getPostingError(), v.getVoidReason(), v.getVoidedAt(), v.getUserId(), v.getVersion(),
                    v.getLines().stream().map(LineView::of).toList(), v.getSource(), v.getSourceRef(),
                    v.getSupplierId(), v.getSupplierName(), v.getDueDate(), v.getPaidAmount(), v.openAmount());
        }
    }
}
