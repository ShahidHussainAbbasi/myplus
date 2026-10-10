package com.myplus.expense.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.myplus.commerce.contracts.client.DrawerHistoryClient;
import com.myplus.commerce.contracts.client.FarmHistoryClient;
import com.myplus.commerce.contracts.dto.FarmExpenseView;
import com.myplus.commerce.contracts.dto.DrawerExpenseRequest;
import com.myplus.commerce.contracts.dto.DrawerPayoutView;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/**
 * EX-9a (R-4) — past till pay-outs brought into the books, with the owner's consent: listed first (preview), imported
 * only as the owner ticks them, each exactly once.
 *
 * <h3>Once per pay-out, whichever path books it</h3>
 * The key is EX-3's own, {@code (DRAWER, movementId)}, through {@link ExpenseVoucherService#recordFromDrawer}: a pay-out
 * that already became an expense (by EX-3, or by an earlier import) is never listed and never booked again.
 *
 * <h3>Never the browser's rows</h3>
 * Import receives only ids. It re-reads business-service's list for the caller's business and books only ids that are
 * still there, with business's own date, amount and reason.
 */
@Service
public class ExpenseHistoryService {

    private static final Logger LOG = LoggerFactory.getLogger(ExpenseHistoryService.class);

    /** One past pay-out as the owner sees it before importing; {@code matches} = expenses it may duplicate. */
    public record TillRow(Long ref, java.time.LocalDate date, BigDecimal amount, String reason, Long storeId, List<String> matches) { }

    public record ImportResult(int imported, int skipped, List<String> voucherNos) { }

    /** EX-9b — one old farm expense row as the owner sees it before importing. */
    public record FarmRow(Long ref, java.time.LocalDate date, BigDecimal amount, String reason, String expenseType,
                          Long landId, String landName, String cropName, List<String> matches) { }

    private final ExpenseVoucherRepo repo;
    private final ExpenseAccess access;
    private final ExpenseVoucherService vouchers;
    private final DrawerHistoryClient business;
    private final FarmHistoryClient farm;

    public ExpenseHistoryService(ExpenseVoucherRepo repo, ExpenseAccess access, ExpenseVoucherService vouchers,
                                 DrawerHistoryClient business, FarmHistoryClient farm) {
        this.repo = repo;
        this.access = access;
        this.vouchers = vouchers;
        this.business = business;
        this.farm = farm;
    }

    // ── EX-9b — the farm: the same consent flow over agriculture's rows, keyed (FARM, row id) ──────────────────────

    /** The farm's old expense rows not yet in the books, each with the expenses it may duplicate. Owner/admin. */
    public List<FarmRow> farmPreview() {
        guard();
        Long org = access.org();
        List<FarmRow> out = new ArrayList<>();
        for (FarmExpenseView f : farmRows()) {
            if (booked(org, ExpenseVoucher.SOURCE_FARM, f.getId())) continue;
            out.add(new FarmRow(f.getId(), f.getDate(), f.getAmount(), f.getExpenseName(), f.getExpenseType(), f.getLandId(),
                    f.getLandName(), f.getCropName(), matches(org, f.getDate(), f.getAmount())));
        }
        return out;
    }

    /** Import the ticked farm rows under one category; ids not (or no longer) waiting on this farm are skipped. */
    public ImportResult importFarm(Long categoryId, List<Long> refs) {
        guard();
        checkRequest(categoryId, refs);
        Long org = access.org();
        Map<Long, FarmExpenseView> fresh = farmRows().stream()
                .collect(Collectors.toMap(FarmExpenseView::getId, Function.identity(), (a, b) -> a));
        int imported = 0, skipped = 0;
        List<String> numbers = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long ref : refs) {
            FarmExpenseView f = ref == null ? null : fresh.get(ref);
            if (f == null || !seen.add(ref) || booked(org, ExpenseVoucher.SOURCE_FARM, ref)) {
                skipped++;
                if (ref != null) repo.findByOrganizationIdAndSourceAndSourceRef(org, ExpenseVoucher.SOURCE_FARM, String.valueOf(ref))
                        .ifPresent(v -> stampFarm(ref, v.getVoucherNo()));
                continue;
            }
            var made = vouchers.recordFromFarm(f, categoryId);
            imported++;
            if (made.getVoucherNo() != null) numbers.add(made.getVoucherNo());
            stampFarm(ref, made.getVoucherNo());
        }
        return new ImportResult(imported, skipped, numbers);
    }

    private List<FarmExpenseView> farmRows() {
        try {
            List<FarmExpenseView> l = farm.notInBooks();
            return l == null ? List.of() : l;
        } catch (Exception e) {
            LOG.warn("EX-9b: agriculture-service did not answer for past farm expenses", e);
            throw new ValidationException("Could not read the farm's past expenses. Try again in a moment.");
        }
    }

    private void stampFarm(Long ref, String voucherNo) {
        if (ref == null || voucherNo == null) return;
        try {
            farm.stampExpenseVoucher(ref, voucherNo);
        } catch (Exception e) {
            LOG.warn("EX-9b: could not stamp farm row {} with {} (it stays out of the preview by its key)", ref, voucherNo, e);
        }
    }

    private static void checkRequest(Long categoryId, List<Long> refs) {
        if (categoryId == null) throw new ValidationException("Choose the category these expenses were for.");
        if (refs == null || refs.isEmpty()) throw new ValidationException("Tick the expenses to bring into the books.");
        if (refs.size() > 500) throw new ValidationException("At most 500 at a time.");
    }

    private boolean booked(Long org, String source, Long ref) {
        return ref != null && repo.findByOrganizationIdAndSourceAndSourceRef(org, source, String.valueOf(ref)).isPresent();
    }

    /** The business's past pay-outs not yet in the books, each with the expenses it may duplicate. Owner/admin. */
    public List<TillRow> tillPreview() {
        guard();
        Long org = access.org();
        List<TillRow> out = new ArrayList<>();
        for (DrawerPayoutView p : unbooked()) {
            if (alreadyBooked(org, p.getMovementId())) continue;
            out.add(new TillRow(p.getMovementId(), p.getDate(), p.getAmount(), p.getReason(), p.getStoreId(), matches(org, p.getDate(), p.getAmount())));
        }
        return out;
    }

    /** Import the ticked pay-outs under one category. Ids not (or no longer) unbooked in this business are skipped. */
    public ImportResult importTill(Long categoryId, List<Long> refs) {
        guard();
        if (categoryId == null) throw new ValidationException("Choose the category these pay-outs were for.");
        if (refs == null || refs.isEmpty()) throw new ValidationException("Tick the pay-outs to bring into the books.");
        if (refs.size() > 500) throw new ValidationException("At most 500 pay-outs at a time.");
        Long org = access.org();
        Map<Long, DrawerPayoutView> fresh = unbooked().stream()
                .collect(Collectors.toMap(DrawerPayoutView::getMovementId, Function.identity(), (a, b) -> a));
        int imported = 0, skipped = 0;
        List<String> numbers = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long ref : refs) {
            DrawerPayoutView p = ref == null ? null : fresh.get(ref);
            if (p == null || !seen.add(ref) || alreadyBooked(org, ref)) {
                skipped++;
                stampIfBooked(org, ref);           // an earlier import whose stamp did not reach business: try again
                continue;
            }
            var ref1 = vouchers.recordFromDrawer(DrawerExpenseRequest.builder()
                    .movementId(p.getMovementId()).categoryId(categoryId).amount(p.getAmount())
                    .date(p.getDate()).storeId(p.getStoreId()).reason(p.getReason()).build());
            imported++;
            if (ref1.getVoucherNo() != null) numbers.add(ref1.getVoucherNo());
            stamp(ref, ref1.getVoucherNo());
        }
        return new ImportResult(imported, skipped, numbers);
    }

    private void guard() {
        access.assertModuleOn();
        if (!access.canApprove()) throw new AccessDeniedException("Only an owner or admin brings past pay-outs into the books.");
    }

    private List<DrawerPayoutView> unbooked() {
        try {
            List<DrawerPayoutView> l = business.unbookedPayouts();
            return l == null ? List.of() : l;
        } catch (Exception e) {
            LOG.warn("EX-9a: business-service did not answer for past pay-outs", e);
            throw new ValidationException("Could not read the till's past pay-outs. Try again in a moment.");
        }
    }

    private boolean alreadyBooked(Long org, Long ref) {
        return ref != null && repo.findByOrganizationIdAndSourceAndSourceRef(org, ExpenseVoucher.SOURCE_DRAWER, String.valueOf(ref)).isPresent();
    }

    private List<String> matches(Long org, java.time.LocalDate date, BigDecimal amount) {
        if (date == null || amount == null) return List.of();
        return repo.sameDayAmount(org, date, amount.setScale(2, RoundingMode.HALF_UP)).stream()
                .map(v -> v.getVoucherNo() != null ? v.getVoucherNo()
                        : v.isClaim() ? "a claim waiting for approval" : "an expense waiting to be posted")
                .distinct().toList();
    }

    /** Best effort: the expense side's key already prevents a second booking; the stamp only tidies business's list. */
    private void stamp(Long ref, String voucherNo) {
        if (ref == null || voucherNo == null) return;
        try {
            business.stampExpenseVoucher(ref, voucherNo);
        } catch (Exception e) {
            LOG.warn("EX-9a: could not stamp pay-out {} with {} (it stays out of the preview by its key)", ref, voucherNo, e);
        }
    }

    private void stampIfBooked(Long org, Long ref) {
        if (ref == null) return;
        repo.findByOrganizationIdAndSourceAndSourceRef(org, ExpenseVoucher.SOURCE_DRAWER, String.valueOf(ref))
                .ifPresent(v -> stamp(ref, v.getVoucherNo()));
    }
}
