package com.myplus.business_service.service;

import com.myplus.common.security.time.TenantClock;

import com.myplus.business_service.dto.ShiftReportDTO;
import com.myplus.business_service.entity.*;
import com.myplus.business_service.repository.CashMovementRepo;
import com.myplus.business_service.repository.CashierShiftRepo;
import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.repository.PaymentRepo;
import com.myplus.common.web.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Cashier shift / cash-drawer / X-Z report (POS day-close, slice 39). One OPEN shift per cashier; sales are stamped
 * with the open shift (in the saga writer). The cash math ({@link #expectedCash}) is pure and unit-tested.
 */
@Service
@RequiredArgsConstructor
public class ShiftService {

    private final CashierShiftRepo shiftRepo;
    private final CashMovementRepo movementRepo;
    private final CustomerHistoryRepo customerHistoryRepo;
    private final com.myplus.business_service.util.RequestUtil requestUtil;   // multi-location: the active store
    private final PaymentRepo paymentRepo;
    private final DrawerExpenseOutboxService drawerExpenses;   // EX-3 — pay-outs to the books, after commit

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    /** Drawer cash that should be present: float + cash sales + refunds(neg) + pay-ins − pay-outs − drops. */
    public static BigDecimal expectedCash(BigDecimal openingFloat, BigDecimal cashSales, BigDecimal refunds,
                                          BigDecimal payIns, BigDecimal payOuts, BigDecimal drops) {
        return nz(openingFloat).add(nz(cashSales)).add(nz(refunds))
                .add(nz(payIns)).subtract(nz(payOuts)).subtract(nz(drops));
    }

    public Optional<CashierShift> currentOpenShift(Long orgId, Long userId) {
        return shiftRepo.findFirstByOrganizationIdAndUserIdAndStatusOrderByOpenedAtDesc(orgId, userId, ShiftStatus.OPEN);
    }

    @Transactional
    public CashierShift openShift(BigDecimal openingFloat, Long orgId, Long userId) {
        if (currentOpenShift(orgId, userId).isPresent())
            throw new ValidationException("A shift is already open. Close it before opening a new one.");
        return shiftRepo.save(CashierShift.builder()
                .organizationId(orgId).userId(userId)
                .storeId(requestUtil.activeStoreId())      // a shift belongs to the store whose till it opens
                .openingFloat(nz(openingFloat))
                .openedAt(LocalDateTime.now())
                .status(ShiftStatus.OPEN)
                .build());
    }

    @Transactional
    public CashMovement addCashMovement(MovementType type, BigDecimal amount, String reason, Long orgId, Long userId) {
        return addCashMovement(type, amount, reason, null, null, false, orgId, userId);
    }

    /**
     * Record a drawer movement — and, for a PAY_OUT while Expense management is on, send it to the books (EX-3).
     *
     * <h3>Idempotency first</h3>
     * A replayed {@code idempotencyKey} returns the movement it already made: a double click or a retry after a
     * dropped connection must not take the cash out twice. UNIQUE (organization_id, idempotency_key) (V70) carries
     * the guarantee; this lookup is only the fast path.
     *
     * <h3>The capability is decided HERE, where the cash moves</h3>
     * {@code expenseModuleOn} comes from the caller's token. OFF → exactly the behaviour before EX-3: no category
     * asked, nothing posted. ON → a pay-out must name a category, and the movement and its outbox row commit in
     * this one transaction. PAY_IN and DROP never become expenses — a drop is cash moved to the safe, not spent.
     */
    @Transactional
    public CashMovement addCashMovement(MovementType type, BigDecimal amount, String reason, Long categoryId,
                                        String idempotencyKey, boolean expenseModuleOn, Long orgId, Long userId) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null) {
            if (key.length() > 80) throw new ValidationException("Idempotency key is too long.");
            Optional<CashMovement> replay = movementRepo.findByOrganizationIdAndIdempotencyKey(orgId, key);
            if (replay.isPresent()) return replay.get();
        }
        CashierShift shift = currentOpenShift(orgId, userId)
                .orElseThrow(() -> new ValidationException("No open shift — open the till first."));
        if (type == null) throw new ValidationException("Movement type is required.");
        if (nz(amount).signum() <= 0) throw new ValidationException("Amount must be greater than 0.");
        boolean toBooks = expenseModuleOn && type == MovementType.PAY_OUT;
        if (toBooks && categoryId == null)
            throw new ValidationException("Choose what the money was paid for (a category), so it reaches your books.");
        CashMovement saved = movementRepo.save(CashMovement.builder()
                .organizationId(orgId).userId(userId).shiftId(shift.getId())
                .storeId(shift.getStoreId())               // follow the shift's store, not the caller's — they cannot differ
                .type(type).amount(amount.abs()).reason(reason)
                .categoryId(toBooks ? categoryId : null)
                .idempotencyKey(key)
                .build());
        if (toBooks) {
            drawerExpenses.enqueue(orgId, userId, com.myplus.commerce.contracts.dto.DrawerExpenseRequest.builder()
                    .movementId(saved.getId()).categoryId(categoryId).amount(saved.getAmount())
                    .date(TenantClock.today()).storeId(saved.getStoreId()).reason(reason)
                    .build());
        }
        return saved;
    }

    /** X report — live totals for the cashier's open shift (no state change). */
    public ShiftReportDTO reportX(Long orgId, Long userId) {
        CashierShift shift = currentOpenShift(orgId, userId)
                .orElseThrow(() -> new ValidationException("No open shift."));
        return buildReport(shift);
    }

    /** Z report — close the shift, recording counted cash + variance. */
    @Transactional
    public ShiftReportDTO closeShift(BigDecimal countedCash, String notes, Long orgId, Long userId) {
        CashierShift shift = currentOpenShift(orgId, userId)
                .orElseThrow(() -> new ValidationException("No open shift to close."));
        ShiftReportDTO report = buildReport(shift);
        shift.setStatus(ShiftStatus.CLOSED);
        shift.setClosedAt(LocalDateTime.now());
        shift.setCountedCash(nz(countedCash));
        shift.setExpectedCash(report.getExpectedCash());
        shift.setVariance(nz(countedCash).subtract(report.getExpectedCash()));
        shift.setNotes(notes);
        shiftRepo.save(shift);
        report.setStatus(ShiftStatus.CLOSED.name());
        report.setClosedAt(shift.getClosedAt());
        report.setCountedCash(shift.getCountedCash());
        report.setVariance(shift.getVariance());
        return report;
    }

    private ShiftReportDTO buildReport(CashierShift shift) {
        ShiftReportDTO r = new ShiftReportDTO();
        r.setShiftId(shift.getId());
        r.setStatus(shift.getStatus().name());
        r.setOpenedAt(shift.getOpenedAt());
        r.setOpeningFloat(nz(shift.getOpeningFloat()));

        Object[] s = customerHistoryRepo.shiftSalesSummary(shift.getId());
        // single-row aggregate may arrive as Object[] or as Object[]{Object[]}; normalise.
        Object[] row = (s != null && s.length == 1 && s[0] instanceof Object[]) ? (Object[]) s[0] : s;
        if (row != null && row.length >= 3) {
            r.setSalesCount(row[0] == null ? 0L : ((Number) row[0]).longValue());
            r.setSalesGross(row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]);
            r.setTaxTotal(row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2]);
        }

        for (Object[] m : paymentRepo.sumByMethodForShift(shift.getId())) {
            String method = String.valueOf(m[0]);
            BigDecimal sum = m[1] == null ? BigDecimal.ZERO : (BigDecimal) m[1];
            r.getByMethod().put(method, sum);
        }
        r.setCashSales(r.getByMethod().getOrDefault(PaymentMethod.CASH.name(), BigDecimal.ZERO));
        r.setRefunds(r.getByMethod().getOrDefault(PaymentMethod.REFUND.name(), BigDecimal.ZERO));

        r.setPayIns(movementRepo.sumByShiftAndType(shift.getId(), MovementType.PAY_IN));
        r.setPayOuts(movementRepo.sumByShiftAndType(shift.getId(), MovementType.PAY_OUT));
        r.setDrops(movementRepo.sumByShiftAndType(shift.getId(), MovementType.DROP));

        r.setExpectedCash(expectedCash(r.getOpeningFloat(), r.getCashSales(), r.getRefunds(),
                r.getPayIns(), r.getPayOuts(), r.getDrops()));
        return r;
    }
}
