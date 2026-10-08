package com.myplus.marketplace.multiseller.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.StockHoldRequest;
import com.myplus.commerce.contracts.dto.StockHoldResponse;
import com.myplus.marketplace.support.AsOrg;

import jakarta.annotation.PreDestroy;

/**
 * MKT-2c — the live half of routing (source §18.1, §18.3, §18.5): a seller's stock is asked for with a deadline,
 * and a seller that keeps failing to answer is not asked again for a while.
 * Contract: docs/slices/mkt-2c-live-routing.md
 *
 * <h3>What it bounds</h3>
 * <ul>
 *   <li><b>One seller:</b> {@code holdTimeout} (800 ms by default, the top of the source's 300–800 ms).</li>
 *   <li><b>One checkout:</b> {@code deadline} (2 s) across every seller in it. The parts are asked at the same time,
 *       so a basket from three sellers waits for the slowest one, not for the sum.</li>
 *   <li><b>A seller that keeps failing:</b> after {@code breakerFailures} timeouts or errors in a row, its circuit
 *       opens for {@code breakerOpen}. While it is open the seller is refused at once, without a call. After that one
 *       trial call is let through: an answer closes it, another failure opens it again.</li>
 * </ul>
 * A refusal ("not enough stock") is an answer: it never counts against the seller.
 *
 * <h3>The call it stops waiting for is not cancelled</h3>
 * The remote hold may still land after the shopper was told no. Every hold is taken under a key that belongs only to
 * this attempt, so when an abandoned call finishes, anything it may have held is released by that key (inventory
 * re-arms a RELEASED key on a later reserve, so the order's own release cannot cover a hold that lands after it).
 *
 * <h3>Per instance</h3>
 * The circuits are kept in memory. With two instances each learns on its own; the worst case is that a seller is
 * tried {@code breakerFailures} times per instance.
 *
 * <h3>The operator's test switch</h3>
 * {@code mkt.routing.test-switch=true} (off by default, never on in production) lets an operator make one seller slow
 * from the platform screen, so the deadline can be seen working. With the property off the stored value is ignored.
 */
@Component
public class LiveRouting {

    private static final Logger LOG = LoggerFactory.getLogger(LiveRouting.class);

    /** What a seller's stock answered. */
    public enum Kind { HELD, REFUSED, TIMED_OUT, NOT_ANSWERING }

    public record Answer(Kind kind, String reason) {
        public boolean held() { return kind == Kind.HELD; }
        /** The seller did not answer in time, or its circuit is open: the shopper hears "did not answer in time". */
        public boolean silent() { return kind == Kind.TIMED_OUT || kind == Kind.NOT_ANSWERING; }
    }

    /** One hold to take: as {@code seller}, under {@code key} (released by that key if the call is abandoned). */
    public record Ask(Long seller, String key, StockHoldRequest request) {}

    /** A seller whose circuit is open, for the operator. */
    public record OpenCircuit(Long sellerOrganizationId, int failures, LocalDateTime until) {}

    /** The limits in force, for the operator. */
    public record Limits(long holdTimeoutMs, long deadlineMs, int breakerFailures, long breakerOpenSeconds, boolean testSwitch) {}

    private final TradeClient trade;
    private final Duration holdTimeout;
    private final Duration deadline;
    private final int breakerFailures;
    private final Duration breakerOpen;
    private final boolean testSwitch;
    private final LongSupplier nanos;
    /** The test switch's stored value; read per call, only when the switch property is on. */
    private final Supplier<MarketplaceSettingsService.SlowSeller> slowSeller;
    private final ThreadPoolExecutor pool;
    private final Map<Long, Circuit> circuits = new ConcurrentHashMap<>();

    @Autowired
    public LiveRouting(TradeClient trade, MarketplaceSettingsService settings,
            @Value("${mkt.routing.hold-timeout-ms:800}") long holdTimeoutMs,
            @Value("${mkt.routing.deadline-ms:2000}") long deadlineMs,
            @Value("${mkt.routing.breaker-failures:3}") int breakerFailures,
            @Value("${mkt.routing.breaker-open-seconds:30}") long breakerOpenSeconds,
            @Value("${mkt.routing.test-switch:false}") boolean testSwitch) {
        this(trade, Duration.ofMillis(holdTimeoutMs), Duration.ofMillis(deadlineMs), breakerFailures,
                Duration.ofSeconds(breakerOpenSeconds), testSwitch, System::nanoTime, settings::slowSeller);
    }

    LiveRouting(TradeClient trade, Duration holdTimeout, Duration deadline, int breakerFailures, Duration breakerOpen,
            boolean testSwitch, LongSupplier nanos, Supplier<MarketplaceSettingsService.SlowSeller> slowSeller) {
        if (holdTimeout.isNegative() || holdTimeout.isZero() || deadline.compareTo(holdTimeout) < 0 || breakerFailures < 1)
            throw new IllegalArgumentException("routing limits: 0 < hold timeout <= deadline, at least one failure");
        this.trade = trade;
        this.holdTimeout = holdTimeout;
        this.deadline = deadline;
        this.breakerFailures = breakerFailures;
        this.breakerOpen = breakerOpen;
        this.testSwitch = testSwitch;
        this.nanos = nanos;
        this.slowSeller = slowSeller;
        AtomicInteger n = new AtomicInteger();
        // a direct hand-off: when every thread is busy the seller is "not answering", never queued behind the others
        this.pool = new ThreadPoolExecutor(4, 64, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "mkt-route-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    /** The overall deadline for one routing decision, starting now (as a {@link #nanos} value). */
    public long deadlineFromNow() {
        return nanos.getAsLong() + deadline.toNanos();
    }

    public Limits limits() {
        return new Limits(holdTimeout.toMillis(), deadline.toMillis(), breakerFailures, breakerOpen.toSeconds(), testSwitch);
    }

    /** One seller, waiting at most the per-seller timeout and never past {@code until}. */
    public Answer hold(Ask ask, long until) {
        return holdAll(List.of(ask), until).get(0);
    }

    /**
     * Every seller at once; each answer waits at most the per-seller timeout from when it was asked, and none waits
     * past {@code until}. The answers come back in the order asked.
     */
    public List<Answer> holdAll(List<Ask> asks, long until) {
        List<Call> calls = new ArrayList<>();
        for (Ask a : asks) calls.add(start(a));
        List<Answer> out = new ArrayList<>();
        for (Call c : calls) out.add(await(c, until));
        return out;
    }

    /** The sellers not being asked right now, for the operator. */
    public List<OpenCircuit> openCircuits() {
        long now = nanos.getAsLong();
        LocalDateTime wall = LocalDateTime.now();
        List<OpenCircuit> out = new ArrayList<>();
        circuits.forEach((seller, c) -> {
            synchronized (c) {
                if (c.openUntil != 0 && now - c.openUntil < 0)
                    out.add(new OpenCircuit(seller, c.lastRun, wall.plusNanos(c.openUntil - now)));
            }
        });
        out.sort(Comparator.comparing(OpenCircuit::sellerOrganizationId));
        return out;
    }

    /** The operator closes a seller's circuit (the seller says it is fixed): its next checkout is asked again. */
    public boolean close(Long seller) {
        return circuits.remove(seller) != null;
    }

    // ── one call ─────────────────────────────────────────────────────────────────────────────────────────

    private static final int RUNNING = 0, ANSWERED = 1, ABANDONED = 2;

    private final class Call {
        final Ask ask;
        final long startedAt;
        final AtomicInteger state = new AtomicInteger(RUNNING);
        Future<Answer> future;
        Answer immediate;
        boolean trial;

        Call(Ask ask, long startedAt) {
            this.ask = ask;
            this.startedAt = startedAt;
        }
    }

    private Call start(Ask ask) {
        Call call = new Call(ask, nanos.getAsLong());
        Circuit circuit = circuits.computeIfAbsent(ask.seller(), k -> new Circuit());
        Boolean allowed = circuit.allow(call.startedAt);
        if (allowed == null) {
            call.immediate = new Answer(Kind.NOT_ANSWERING, "the seller is not answering");
            return call;
        }
        call.trial = allowed;
        try {
            call.future = pool.submit(() -> {
                Answer a = remote(ask);
                if (!call.state.compareAndSet(RUNNING, ANSWERED) && a.kind() != Kind.REFUSED) {
                    // nobody is waiting any more: whatever it may have held goes back, by this attempt's own key
                    LOG.info("MKT routing: late answer from seller {} for {} ({}); releasing", ask.seller(), ask.key(), a.kind());
                    release(ask);
                }
                return a;
            });
        } catch (RejectedExecutionException busy) {
            circuit.abandonTrial(call.trial);
            call.immediate = new Answer(Kind.NOT_ANSWERING, "too many sellers are being asked at once");
        }
        return call;
    }

    private Answer await(Call call, long until) {
        if (call.immediate != null) return call.immediate;
        long wait = Math.min(call.startedAt + holdTimeout.toNanos(), until) - nanos.getAsLong();
        Answer a;
        try {
            a = call.future.get(Math.max(0, wait), TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            if (call.state.compareAndSet(RUNNING, ABANDONED)) {
                a = new Answer(Kind.TIMED_OUT, "the seller did not answer in time");
            } else {
                a = join(call);                                           // it answered as the clock ran out
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.state.set(ABANDONED);
            a = new Answer(Kind.TIMED_OUT, "interrupted");
        } catch (ExecutionException e) {
            a = new Answer(Kind.NOT_ANSWERING, "the stock could not be checked");
        }
        record(call, a);
        return a;
    }

    private Answer join(Call call) {
        try {
            return call.future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(Kind.TIMED_OUT, "interrupted");
        } catch (ExecutionException e) {
            return new Answer(Kind.NOT_ANSWERING, "the stock could not be checked");
        }
    }

    /** Only a seller that did not answer counts against it; "not enough stock" is an answer. */
    private void record(Call call, Answer a) {
        Circuit c = circuits.computeIfAbsent(call.ask.seller(), k -> new Circuit());
        boolean failed = a.kind() == Kind.TIMED_OUT || a.kind() == Kind.NOT_ANSWERING;
        if (failed && c.failure(nanos.getAsLong(), call.trial))
            LOG.warn("MKT routing: seller {} stopped answering; not asked for {}s", call.ask.seller(), breakerOpen.toSeconds());
        if (!failed) c.success();
    }

    /** The remote hold itself, on a pool thread. Never throws. */
    private Answer remote(Ask ask) {
        try {
            if (testSwitch) {
                MarketplaceSettingsService.SlowSeller slow = slowSeller.get();
                if (slow != null && slow.sellerOrganizationId().equals(ask.seller())) Thread.sleep(slow.delayMs());
            }
            StockHoldResponse r = AsOrg.call(ask.seller(), () -> trade.holdStock(ask.request()));
            if (r == null) return new Answer(Kind.NOT_ANSWERING, "inventory did not answer");
            return r.isHeld() ? new Answer(Kind.HELD, null) : new Answer(Kind.REFUSED, r.getReason());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(Kind.NOT_ANSWERING, "interrupted");
        } catch (RuntimeException e) {
            LOG.warn("MKT hold {} failed: {}", ask.key(), e.toString());
            return new Answer(Kind.NOT_ANSWERING, "the stock could not be checked");
        }
    }

    private void release(Ask ask) {
        try {
            AsOrg.run(ask.seller(), () -> trade.releaseHold(ask.key()));
        } catch (RuntimeException e) {
            LOG.warn("MKT routing: could not release late hold {}: {}", ask.key(), e.toString());
        }
    }

    // ── the circuit ──────────────────────────────────────────────────────────────────────────────────────

    /** Closed → open after N failures in a row → one trial after the open period → closed or open again. */
    private final class Circuit {
        int failures;
        int lastRun;
        long openUntil;          // nanos; 0 = closed
        boolean trialOut;

        /** @return null to refuse without a call; FALSE for an ordinary call; TRUE for the one trial call */
        synchronized Boolean allow(long now) {
            if (openUntil == 0) return Boolean.FALSE;
            if (now - openUntil < 0 || trialOut) return null;
            trialOut = true;
            return Boolean.TRUE;
        }

        synchronized void abandonTrial(boolean trial) {
            if (trial) trialOut = false;
        }

        synchronized void success() {
            failures = 0;
            openUntil = 0;
            trialOut = false;
        }

        /** @return true when this failure opened the circuit */
        synchronized boolean failure(long now, boolean trial) {
            if (trial) {                                    // the trial failed: open again for the whole period
                trialOut = false;
                openUntil = now + breakerOpen.toNanos();
                if (openUntil == 0) openUntil = 1;
                return true;
            }
            if (openUntil != 0) return false;               // a call that started before it opened
            if (++failures < breakerFailures) return false;
            lastRun = failures;
            failures = 0;
            openUntil = now + breakerOpen.toNanos();
            if (openUntil == 0) openUntil = 1;
            return true;
        }
    }
}
