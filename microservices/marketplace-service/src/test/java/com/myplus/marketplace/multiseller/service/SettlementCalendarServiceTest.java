package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.security.time.TenantClock;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.dto.SettlementDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceHoliday;
import com.myplus.marketplace.multiseller.repository.MarketplaceHolidayRepository;

/** MKT-2f — bank holidays: only a future weekday, once, with a name; operator only; the calendar skips them. */
@ExtendWith(MockitoExtension.class)
class SettlementCalendarServiceTest {

    @Mock MarketplaceHolidayRepository repo;
    @Mock MarketplaceAuditService audit;
    @Mock SellerAccess access;
    SettlementCalendarService service;
    final Map<LocalDate, MarketplaceHoliday> table = new TreeMap<>();

    @BeforeEach
    void wire() {
        service = new SettlementCalendarService(repo, audit, access);
        lenient().when(repo.findAll()).thenAnswer(i -> new java.util.ArrayList<>(table.values()));
        lenient().when(repo.findAllByOrderByHolidayDateAsc()).thenAnswer(i -> new java.util.ArrayList<>(table.values()));
        lenient().when(repo.existsById(any())).thenAnswer(i -> table.containsKey(i.getArgument(0)));
        lenient().when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(table.get(i.getArgument(0))));
        lenient().when(repo.save(any())).thenAnswer(i -> { MarketplaceHoliday h = i.getArgument(0); table.put(h.getHolidayDate(), h); return h; });
        lenient().doAnswer(i -> table.remove(((MarketplaceHoliday) i.getArgument(0)).getHolidayDate())).when(repo).delete(any());
    }

    /** The next weekday at least {@code days} after today. */
    static LocalDate weekday(int days) {
        LocalDate d = TenantClock.today().plusDays(days);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.plusDays(1);
        return d;
    }

    static LocalDate saturday() {
        LocalDate d = TenantClock.today().plusDays(1);
        while (d.getDayOfWeek() != DayOfWeek.SATURDAY) d = d.plusDays(1);
        return d;
    }

    @Test
    @DisplayName("[MKT-R15.1] a holiday added is skipped by the calendar, listed as removable, and audited")
    void addAndCalendar() {
        LocalDate d = weekday(10);
        var list = service.add(new SettlementDTOs.HolidayRequest(d, "  Eid ul-Fitr "));
        assertThat(list).containsExactly(new SettlementDTOs.HolidayView(d, "Eid ul-Fitr", true));
        assertThat(service.calendar().isBusinessDay(d)).isFalse();
        assertThat(service.calendar().isBusinessDay(weekday(30))).isTrue();
        verify(audit).event(eq("MKT_HOLIDAY_ADDED"), eq("MKT_HOLIDAY"), eq(d.toString()), any(), any(), any(), eq("Eid ul-Fitr"), any(), any());
    }

    @Test
    @DisplayName("[MKT-R15.1] refused in a sentence: no day, no name, a long name, today or the past, a weekend, twice")
    void addRefused() {
        LocalDate d = weekday(10);
        assertThatThrownBy(() -> service.add(new SettlementDTOs.HolidayRequest(null, "Eid"))).hasMessage("Choose the day.");
        assertThatThrownBy(() -> service.add(new SettlementDTOs.HolidayRequest(d, " "))).hasMessage("Give the holiday a name, for example \"Eid ul-Fitr\".");
        assertThatThrownBy(() -> service.add(new SettlementDTOs.HolidayRequest(d, "x".repeat(81)))).hasMessage("Keep the name under 80 characters.");
        assertThatThrownBy(() -> service.add(new SettlementDTOs.HolidayRequest(TenantClock.today(), "Eid")))
                .hasMessage("A holiday can be added only for a day after today: lines already payable keep their day.");
        assertThatThrownBy(() -> service.add(new SettlementDTOs.HolidayRequest(saturday(), "Eid")))
                .hasMessage("That day is a weekend: nothing is paid on it anyway.");
        service.add(new SettlementDTOs.HolidayRequest(d, "Eid"));
        assertThatThrownBy(() -> service.add(new SettlementDTOs.HolidayRequest(d, "Eid again"))).hasMessage("That day is already a holiday.");
        assertThat(table).hasSize(1);
    }

    @Test
    @DisplayName("[MKT-R15.1] only a future holiday is removed; a past one stays, so no payable day moves")
    void remove() {
        LocalDate future = weekday(10), past = TenantClock.today().minusDays(3);
        MarketplaceHoliday old = new MarketplaceHoliday();
        old.setHolidayDate(past);
        old.setName("Kashmir Day");
        table.put(past, old);
        service.add(new SettlementDTOs.HolidayRequest(future, "Eid"));
        assertThat(service.list()).extracting(SettlementDTOs.HolidayView::removable).containsExactly(false, true);
        assertThatThrownBy(() -> service.remove(new SettlementDTOs.HolidayRequest(past, null)))
                .isInstanceOf(ValidationException.class).hasMessage("A holiday on or before today cannot be removed: lines were made payable around it.");
        assertThatThrownBy(() -> service.remove(new SettlementDTOs.HolidayRequest(weekday(40), null))).hasMessage("That day is not a holiday.");
        assertThat(service.remove(new SettlementDTOs.HolidayRequest(future, null))).extracting(SettlementDTOs.HolidayView::date).containsExactly(past);
        verify(audit).event(eq("MKT_HOLIDAY_REMOVED"), anyString(), eq(future.toString()), any(), any(), eq("Eid"), any(), any(), any());
    }

    @Test
    @DisplayName("[MKT-R22.1] the holiday list is the operator's")
    void operatorOnly() {
        doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> service.list()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.add(new SettlementDTOs.HolidayRequest(weekday(10), "Eid"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.remove(new SettlementDTOs.HolidayRequest(weekday(10), null))).isInstanceOf(AccessDeniedException.class);
        verify(repo, never()).save(any());
    }
}
