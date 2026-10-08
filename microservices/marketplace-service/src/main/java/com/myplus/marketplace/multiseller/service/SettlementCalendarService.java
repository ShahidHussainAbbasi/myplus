package com.myplus.marketplace.multiseller.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.security.time.TenantClock;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.BusinessDayCalendar;
import com.myplus.marketplace.multiseller.dto.SettlementDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceHoliday;
import com.myplus.marketplace.multiseller.repository.MarketplaceHolidayRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-2f — the settlement calendar: Saturday and Sunday (Pakistani banks), plus the bank holidays the operator lists
 * (slice doc {@code mkt-2f-settlement-reports.md}).
 *
 * <p><b>Only a future day can be added or removed.</b> A line's payable day is computed, never stored
 * ({@code MarketplaceSettlementService.eligibleOn}); walking forward from the end of its return window it looks only at
 * days up to its result. A line already payable has a result of today or earlier, so a change after today cannot move
 * it, and no statement line changes its history.
 */
@Service
@RequiredArgsConstructor
public class SettlementCalendarService {

    static final int MAX_NAME = 80;

    private final MarketplaceHolidayRepository holidays;
    private final MarketplaceAuditService audit;
    private final SellerAccess access;

    /** Weekends and every listed holiday. Read whole: tens of rows a year. */
    @Transactional(readOnly = true)
    public BusinessDayCalendar calendar() {
        return BusinessDayCalendar.saturdaySundayWeekend(holidays.findAll().stream()
                .map(MarketplaceHoliday::getHolidayDate).collect(Collectors.toSet()));
    }

    @Transactional(readOnly = true)
    public List<SettlementDTOs.HolidayView> list() {
        access.assertOperator();
        LocalDate today = TenantClock.today();
        return holidays.findAllByOrderByHolidayDateAsc().stream()
                .map(h -> new SettlementDTOs.HolidayView(h.getHolidayDate(), h.getName(), h.getHolidayDate().isAfter(today)))
                .toList();
    }

    @Transactional
    public List<SettlementDTOs.HolidayView> add(SettlementDTOs.HolidayRequest req) {
        access.assertOperator();
        LocalDate d = req == null ? null : req.date();
        String name = req == null || req.name() == null ? "" : req.name().trim();
        if (d == null) throw new ValidationException("Choose the day.");
        if (name.isEmpty()) throw new ValidationException("Give the holiday a name, for example \"Eid ul-Fitr\".");
        if (name.length() > MAX_NAME) throw new ValidationException("Keep the name under 80 characters.");
        future(d, "A holiday can be added only for a day after today: lines already payable keep their day.");
        if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY)
            throw new ValidationException("That day is a weekend: nothing is paid on it anyway.");
        if (holidays.existsById(d)) throw new ValidationException("That day is already a holiday.");
        MarketplaceHoliday h = new MarketplaceHoliday();
        h.setHolidayDate(d);
        h.setName(name);
        h.setCreatedByUserId(access.userId());
        holidays.save(h);
        audit.event("MKT_HOLIDAY_ADDED", "MKT_HOLIDAY", d.toString(), null, MarketplaceAuditService.Actor.OPERATOR, null,
                name, null, null);
        return list();
    }

    @Transactional
    public List<SettlementDTOs.HolidayView> remove(SettlementDTOs.HolidayRequest req) {
        access.assertOperator();
        LocalDate d = req == null ? null : req.date();
        if (d == null) throw new ValidationException("Choose the day.");
        MarketplaceHoliday h = holidays.findById(d).orElseThrow(() -> new ValidationException("That day is not a holiday."));
        future(d, "A holiday on or before today cannot be removed: lines were made payable around it.");
        holidays.delete(h);
        audit.event("MKT_HOLIDAY_REMOVED", "MKT_HOLIDAY", d.toString(), null, MarketplaceAuditService.Actor.OPERATOR,
                h.getName(), null, null, null);
        return list();
    }

    private static void future(LocalDate d, String refusal) {
        if (!d.isAfter(TenantClock.today())) throw new ValidationException(refusal);
    }
}
