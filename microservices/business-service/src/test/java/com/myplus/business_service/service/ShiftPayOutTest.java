package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.myplus.business_service.entity.CashMovement;
import com.myplus.business_service.entity.CashierShift;
import com.myplus.business_service.entity.MovementType;
import com.myplus.business_service.entity.ShiftStatus;
import com.myplus.business_service.repository.CashMovementRepo;
import com.myplus.business_service.repository.CashierShiftRepo;
import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.repository.PaymentRepo;
import com.myplus.commerce.contracts.dto.DrawerExpenseRequest;
import com.myplus.common.web.exception.ValidationException;

/**
 * EX-3 — a till pay-out reaches the books only when the module is on; a replayed submission never pays out twice.
 */
class ShiftPayOutTest {

    private static final long ORG = 6L, USER = 60L;

    private final CashierShiftRepo shifts = mock(CashierShiftRepo.class);
    private final CashMovementRepo movements = mock(CashMovementRepo.class);
    private final DrawerExpenseOutboxService outbox = mock(DrawerExpenseOutboxService.class);
    private final ShiftService svc = new ShiftService(shifts, movements, mock(CustomerHistoryRepo.class),
            mock(com.myplus.business_service.util.RequestUtil.class), mock(PaymentRepo.class), outbox);

    ShiftPayOutTest() {
        CashierShift s = new CashierShift();
        s.setId(11L);
        s.setStoreId(3L);
        when(shifts.findFirstByOrganizationIdAndUserIdAndStatusOrderByOpenedAtDesc(ORG, USER, ShiftStatus.OPEN))
                .thenReturn(Optional.of(s));
        when(movements.save(any(CashMovement.class))).thenAnswer(i -> {
            CashMovement m = i.getArgument(0);
            m.setId(500L);
            return m;
        });
    }

    @Test @DisplayName("module OFF: a pay-out is exactly as before — no category needed, nothing sent to the books")
    void off_is_unchanged() {
        CashMovement m = svc.addCashMovement(MovementType.PAY_OUT, new BigDecimal("40"), "x", null, "k1", false, ORG, USER);
        assertThat(m.getCategoryId()).isNull();
        verify(outbox, never()).enqueue(anyLong(), anyLong(), any());
    }

    @Test @DisplayName("module ON: a pay-out without a category is refused, and nothing is written")
    void on_needs_category() {
        assertThatThrownBy(() -> svc.addCashMovement(MovementType.PAY_OUT, new BigDecimal("10"), "x", null, "k2", true, ORG, USER))
                .isInstanceOf(ValidationException.class).hasMessageContaining("category");
        verify(movements, never()).save(any());
    }

    @Test @DisplayName("⭐ module ON: the pay-out is enqueued for the books in the same call, keyed by the movement")
    void on_enqueues() {
        svc.addCashMovement(MovementType.PAY_OUT, new BigDecimal("1200"), "Electricity", 41L, "k3", true, ORG, USER);
        ArgumentCaptor<DrawerExpenseRequest> req = ArgumentCaptor.forClass(DrawerExpenseRequest.class);
        verify(outbox).enqueue(eq(ORG), eq(USER), req.capture());
        assertThat(req.getValue().getMovementId()).isEqualTo(500L);
        assertThat(req.getValue().getCategoryId()).isEqualTo(41L);
        assertThat(req.getValue().getAmount()).isEqualByComparingTo("1200");
        assertThat(req.getValue().getStoreId()).as("the shift's store").isEqualTo(3L);
    }

    @Test @DisplayName("a pay-in or a drop never becomes an expense, module on or not")
    void pay_in_and_drop_are_not_expenses() {
        svc.addCashMovement(MovementType.PAY_IN, new BigDecimal("50"), "float", null, "k4", true, ORG, USER);
        svc.addCashMovement(MovementType.DROP, new BigDecimal("900"), "to safe", null, "k5", true, ORG, USER);
        verify(outbox, never()).enqueue(anyLong(), anyLong(), any());
    }

    @Test @DisplayName("⭐ a replayed idempotency key returns the first movement — the cash leaves once")
    void replay_returns_first() {
        CashMovement first = new CashMovement();
        first.setId(77L);
        when(movements.findByOrganizationIdAndIdempotencyKey(ORG, "same")).thenReturn(Optional.of(first));
        CashMovement m = svc.addCashMovement(MovementType.PAY_OUT, new BigDecimal("77"), "x", 41L, "same", true, ORG, USER);
        assertThat(m.getId()).isEqualTo(77L);
        verify(movements, never()).save(any());
        verify(outbox, never()).enqueue(anyLong(), anyLong(), any());
    }
}
