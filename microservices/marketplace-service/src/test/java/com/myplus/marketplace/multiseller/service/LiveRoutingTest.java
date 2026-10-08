package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.StockHoldRequest;
import com.myplus.commerce.contracts.dto.StockHoldResponse;
import com.myplus.commerce.contracts.dto.StockReservationLine;

/** MKT-2c — the deadline, the circuit and the test switch, without a checkout around them. */
class LiveRoutingTest {

    static final Long A = 11L, B = 12L;

    final TradeClient trade = mock(TradeClient.class);
    final AtomicReference<MarketplaceSettingsService.SlowSeller> slow = new AtomicReference<>();

    LiveRouting routing(long timeoutMs, long deadlineMs, long openMs, boolean testSwitch) {
        return new LiveRouting(trade, Duration.ofMillis(timeoutMs), Duration.ofMillis(deadlineMs), 3, Duration.ofMillis(openMs),
                testSwitch, System::nanoTime, slow::get);
    }

    static LiveRouting.Ask ask(Long seller, String key) {
        return new LiveRouting.Ask(seller, key, StockHoldRequest.builder().organizationId(seller).holdKey(key)
                .lines(List.of(new StockReservationLine(1L, BigDecimal.ONE))).build());
    }

    static StockHoldResponse held(boolean h) {
        return StockHoldResponse.builder().held(h).reason(h ? null : "Not enough stock").build();
    }

    void answerAfter(long ms, boolean held) {
        when(trade.holdStock(any())).thenAnswer(i -> {
            if (ms > 0) Thread.sleep(ms);
            return held(held);
        });
    }

    @Test
    @DisplayName("[MKT-R18.3] the overall deadline caps a seller's own timeout")
    void deadlineCapsTimeout() {
        LiveRouting r = routing(800, 800, 30_000, false);
        answerAfter(1000, true);
        long t0 = System.nanoTime();
        LiveRouting.Answer a = r.hold(ask(A, "k1"), System.nanoTime() + Duration.ofMillis(300).toNanos());
        assertThat(a.kind()).isEqualTo(LiveRouting.Kind.TIMED_OUT);
        assertThat((System.nanoTime() - t0) / 1_000_000).isBetween(250L, 600L);
    }

    @Test
    @DisplayName("[MKT-R18.3] 'not enough stock' is an answer: it never opens the circuit")
    void refusalIsAnAnswer() {
        LiveRouting r = routing(500, 1000, 30_000, false);
        answerAfter(0, false);
        for (int i = 0; i < 5; i++) assertThat(r.hold(ask(A, "k" + i), r.deadlineFromNow()).kind()).isEqualTo(LiveRouting.Kind.REFUSED);
        assertThat(r.openCircuits()).isEmpty();
        verify(trade, times(5)).holdStock(any());
        verify(trade, never()).releaseHold(any());
    }

    @Test
    @DisplayName("[MKT-R18.3] open → one trial after the open period: an answer closes it, a failure opens it again")
    void halfOpen() throws Exception {
        LiveRouting r = routing(150, 1000, 400, false);
        answerAfter(300, true);
        for (int i = 0; i < 3; i++) assertThat(r.hold(ask(A, "t" + i), r.deadlineFromNow()).kind()).isEqualTo(LiveRouting.Kind.TIMED_OUT);
        assertThat(r.hold(ask(A, "t3"), r.deadlineFromNow()).kind()).isEqualTo(LiveRouting.Kind.NOT_ANSWERING);
        assertThat(r.hold(ask(B, "b"), r.deadlineFromNow()).kind()).as("another seller is unaffected").isEqualTo(LiveRouting.Kind.TIMED_OUT);
        Thread.sleep(450);
        // the trial fails: open again at once
        assertThat(r.hold(ask(A, "t4"), r.deadlineFromNow()).kind()).isEqualTo(LiveRouting.Kind.TIMED_OUT);
        assertThat(r.hold(ask(A, "t5"), r.deadlineFromNow()).kind()).isEqualTo(LiveRouting.Kind.NOT_ANSWERING);
        Thread.sleep(450);
        answerAfter(0, true);
        assertThat(r.hold(ask(A, "t6"), r.deadlineFromNow()).kind()).as("the trial answers").isEqualTo(LiveRouting.Kind.HELD);
        assertThat(r.openCircuits()).isEmpty();
        assertThat(r.hold(ask(A, "t7"), r.deadlineFromNow()).kind()).isEqualTo(LiveRouting.Kind.HELD);
    }

    @Test
    @DisplayName("[MKT-R18.3] an abandoned call that answers 'not enough stock' late is not released (nothing was held)")
    void lateRefusalNotReleased() throws Exception {
        LiveRouting r = routing(100, 1000, 30_000, false);
        answerAfter(250, false);
        assertThat(r.hold(ask(A, "late"), r.deadlineFromNow()).kind()).isEqualTo(LiveRouting.Kind.TIMED_OUT);
        Thread.sleep(400);
        verify(trade, never()).releaseHold(any());
    }

    @Test
    @DisplayName("[MKT-R18.3] an abandoned call that holds late is released by its own key")
    void lateHoldReleased() {
        LiveRouting r = routing(100, 1000, 30_000, false);
        answerAfter(250, true);
        assertThat(r.hold(ask(A, "late"), r.deadlineFromNow()).kind()).isEqualTo(LiveRouting.Kind.TIMED_OUT);
        verify(trade, org.mockito.Mockito.timeout(1000)).releaseHold("late");
    }

    @Test
    @DisplayName("the test switch slows only its seller, and only when the service was started with it")
    void testSwitch() {
        answerAfter(0, true);
        slow.set(new MarketplaceSettingsService.SlowSeller(A, 400));
        LiveRouting off = routing(200, 1000, 30_000, false);
        assertThat(off.hold(ask(A, "x1"), off.deadlineFromNow()).kind()).as("property off: ignored").isEqualTo(LiveRouting.Kind.HELD);
        LiveRouting on = routing(200, 1000, 30_000, true);
        assertThat(on.hold(ask(B, "x2"), on.deadlineFromNow()).kind()).isEqualTo(LiveRouting.Kind.HELD);
        assertThat(on.hold(ask(A, "x3"), on.deadlineFromNow()).kind()).isEqualTo(LiveRouting.Kind.TIMED_OUT);
        verify(trade, org.mockito.Mockito.timeout(1000)).releaseHold("x3");
        assertThat(on.limits().testSwitch()).isTrue();
        assertThat(off.limits().testSwitch()).isFalse();
    }
}
