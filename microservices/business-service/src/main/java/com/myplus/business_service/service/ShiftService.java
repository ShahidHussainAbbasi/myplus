package com.myplus.business_service.service;

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
    private final PaymentRepo paymentRepo;

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
                .openingFloat(nz(openingFloat))
                .openedAt(LocalDateTime.now())
                .status(ShiftStatus.OPEN)
                .build());
    }

    @Transactional
    public CashMovement addCashMovement(MovementType type, BigDecimal amount, String reason, Long orgId, Long userId) {
        CashierShift shift = currentOpenShift(orgId, userId)
                .orElseThrow(() -> new ValidationException("No open shift — open the till first."));
        if (type == null) throw new ValidationException("Movement type is required.");
        if (nz(amount).signum() <= 0) throw new ValidationException("Amount must be greater than 0.");
        return movementRepo.save(CashMovement.builder()
                .organizationId(orgId).userId(userId).shiftId(shift.getId())
                .type(type).amount(amount.abs()).reason(reason)
                .build());
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
