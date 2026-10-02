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

    public record VoucherRequest(LocalDate voucherDate, String paidFrom, Long storeId, String payeeName, String note,
                                 List<LineRequest> lines) { }

    public record VoidRequest(String reason) { }

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
                              Integer version, List<LineView> lines, String source, String sourceRef) {
        public static VoucherView of(ExpenseVoucher v) {
            return new VoucherView(v.getId(), v.getVoucherNo(), v.getVoucherDate(), v.getPaidFrom(), v.getStoreId(),
                    v.getPayeeName(), v.getNote(), v.getTotal(), v.getStatus(), v.getPostingStatus(),
                    v.getPostingError(), v.getVoidReason(), v.getVoidedAt(), v.getUserId(), v.getVersion(),
                    v.getLines().stream().map(LineView::of).toList(), v.getSource(), v.getSourceRef());
        }
    }
}
