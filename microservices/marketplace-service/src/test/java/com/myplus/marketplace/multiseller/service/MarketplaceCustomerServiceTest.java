package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.entity.MarketplaceCustomer;
import com.myplus.marketplace.multiseller.entity.MarketplaceCustomerSession;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.repository.MarketplaceCustomerRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceCustomerSessionRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;

/** MKT-1e2 — the phone + password account: proof before any order is shown, a hashed token, a lock after 5. */
@ExtendWith(MockitoExtension.class)
class MarketplaceCustomerServiceTest {

    @Mock MarketplaceCustomerRepository customers;
    @Mock MarketplaceCustomerSessionRepository sessions;
    @Mock MarketplaceOrderRepository orders;
    MarketplaceCustomerService svc;

    final Map<Long, MarketplaceCustomer> customerTable = new HashMap<>();
    final Map<String, MarketplaceCustomerSession> sessionTable = new HashMap<>();
    final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void wire() {
        svc = new MarketplaceCustomerService(customers, sessions, orders);
        lenient().when(customers.findByPhone(anyString())).thenAnswer(i -> customerTable.values().stream()
                .filter(c -> c.getPhone().equals(i.getArgument(0))).findFirst());
        lenient().when(customers.findById(any())).thenAnswer(i -> Optional.ofNullable(customerTable.get((Long) i.getArgument(0))));
        lenient().when(customers.saveAndFlush(any())).thenAnswer(i -> save(i.getArgument(0)));
        lenient().when(customers.save(any())).thenAnswer(i -> save(i.getArgument(0)));
        lenient().when(sessions.save(any())).thenAnswer(i -> {
            MarketplaceCustomerSession s = i.getArgument(0);
            sessionTable.put(s.getTokenHash(), s);
            return s;
        });
        lenient().when(sessions.findByTokenHash(anyString())).thenAnswer(i -> Optional.ofNullable(sessionTable.get((String) i.getArgument(0))));
    }

    private MarketplaceCustomer save(MarketplaceCustomer c) {
        if (c.getId() == null) c.setId(ids.incrementAndGet());
        customerTable.put(c.getId(), c);
        return c;
    }

    @Test
    @DisplayName("[MKT-R1.1] register: the phone is stored as digits, one account per phone however it is written")
    void registerOnePerPhone() {
        MarketplaceCustomerService.Signed s = svc.register("0311-1234567", "Ali Raza", "Shop!ng2026", null);
        assertThat(customerTable.get(s.customerId()).getPhone()).isEqualTo("03111234567");
        assertThatThrownBy(() -> svc.register("(0311) 1234567", "Other", "Another#2026", null))
                .hasMessageContaining("already has an account");
    }

    @Test
    @DisplayName("[MKT-R22.3] a weak password is refused: short, common, or the phone number itself")
    void passwordRules() {
        assertThatThrownBy(() -> svc.register("03111234567", "Ali", "short", null)).hasMessageContaining("at least 8");
        assertThatThrownBy(() -> svc.register("03111234567", "Ali", "password1", null)).hasMessageContaining("too easy");
        assertThatThrownBy(() -> svc.register("03111234567", "Ali", "03111234567", null)).hasMessageContaining("too easy");
    }

    @Test
    @DisplayName("[MKT-R22.3] the session token is never stored: only its SHA-256; sign-out ends it")
    void tokenHashed() {
        MarketplaceCustomerService.Signed s = svc.register("03111234567", "Ali Raza", "Shop!ng2026", null);
        assertThat(sessionTable).doesNotContainKey(s.token());
        assertThat(sessionTable).containsKey(MarketplaceCustomerService.hash(s.token()));
        assertThat(svc.authenticate(s.token()).getName()).isEqualTo("Ali Raza");
        svc.logout(s.token());
        assertThatThrownBy(() -> svc.authenticate(s.token())).hasMessage(MarketplaceCustomerService.SIGN_IN);
    }

    @Test
    @DisplayName("[MKT-R22.3] an expired session is refused")
    void expired() {
        MarketplaceCustomerService.Signed s = svc.register("03111234567", "Ali Raza", "Shop!ng2026", null);
        sessionTable.get(MarketplaceCustomerService.hash(s.token())).setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThatThrownBy(() -> svc.authenticate(s.token())).hasMessage(MarketplaceCustomerService.SIGN_IN);
    }

    @Test
    @DisplayName("[MKT-R22.3] five wrong passwords lock the phone; the right one is then refused too; unknown reads the same")
    void lockout() {
        svc.register("03111234567", "Ali Raza", "Shop!ng2026", null);
        for (int i = 0; i < MarketplaceCustomerService.MAX_FAILED; i++)
            assertThatThrownBy(() -> svc.login("03111234567", "wrong-one")).hasMessage(MarketplaceCustomerService.WRONG);
        assertThatThrownBy(() -> svc.login("03111234567", "Shop!ng2026")).hasMessageContaining("try again in 15 minutes");
        assertThatThrownBy(() -> svc.login("03119999999", "Shop!ng2026")).hasMessage(MarketplaceCustomerService.WRONG);
    }

    @Test
    @DisplayName("[MKT-R22.1] claim needs the order's phone; an order owned by another account is never moved")
    void claim() {
        MarketplaceCustomer me = save(customer("03111234567"));
        MarketplaceCustomer other = save(customer("03117654321"));
        MarketplaceOrder o = new MarketplaceOrder();
        o.setOrderNo("MKT-000042");
        o.setCustomerPhone("03005556667");
        lenient().when(orders.findByOrderNo("MKT-000042")).thenReturn(Optional.of(o));
        assertThatThrownBy(() -> svc.claim(me, "mkt-000042", "03001112223")).hasMessageContaining("No such order");
        assertThat(svc.claim(me, " mkt-000042 ", "(0300) 555-6667").getCustomerId()).isEqualTo(me.getId());
        assertThat(svc.claim(me, "MKT-000042", "03005556667").getCustomerId()).as("again: same owner").isEqualTo(me.getId());
        assertThatThrownBy(() -> svc.claim(other, "MKT-000042", "03005556667"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("already in another account");
    }

    private static MarketplaceCustomer customer(String phone) {
        MarketplaceCustomer c = new MarketplaceCustomer();
        c.setPhone(phone);
        c.setName("x");
        c.setPasswordHash("x");
        return c;
    }
}
