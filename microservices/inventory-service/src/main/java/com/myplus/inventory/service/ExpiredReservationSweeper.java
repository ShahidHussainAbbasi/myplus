package com.myplus.inventory.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.myplus.inventory.entity.Reservation;
import com.myplus.inventory.repository.ReservationRepository;

import lombok.RequiredArgsConstructor;

/**
 * OMS O5a — returns stock held by reservations nobody ever came back for (fixes OMS-6).
 *
 * <h3>What this repairs</h3>
 * {@code SagaSellService} logs "held stock will lapse/cleanup later" when a compensating release fails. Nothing
 * lapsed and nothing cleaned up, because neither a deadline nor this class existed. The consequence was not a
 * delayed sale: availability is {@code quantity - reservedQuantity}, so a stranded hold made stock permanently
 * unsellable while it went on being counted in on-hand.
 *
 * <h3>Bounded on purpose</h3>
 * The first run after this ships may meet a backlog accumulated over months. It takes a page at a time and runs
 * again shortly. There is no deadline on cleaning up an old leak, and one unbounded transaction across every
 * reservation on the platform would be its own outage.
 *
 * <p>Freeing a single hold — including the locking that stops it racing a confirm — belongs to
 * {@link ReservationExpiryWorker}, which is a separate bean so its {@code REQUIRES_NEW} actually takes effect.
 *
 * <p>This job has no tenant identity and never asks for one: each candidate's deadline was stamped from its own
 * organisation at reserve time.
 */
@Service
@RequiredArgsConstructor
public class ExpiredReservationSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(ExpiredReservationSweeper.class);

    private final ReservationRepository reservationRepository;
    private final ReservationExpiryWorker worker;

    /** How many holds one pass may free. */
    @Value("${inventory.reservation.sweepBatchSize:200}")
    private int batchSize;

    /** Master switch, for an operator who needs the sweep to stop while they investigate. */
    @Value("${inventory.reservation.sweepEnabled:true}")
    private boolean enabled;

    /**
     * Scheduled pass over every tenant. Five minutes by default: frequent enough that a leaked hold costs
     * minutes rather than days, rare enough to be invisible next to normal traffic. The initial delay keeps it
     * out of the way of startup.
     */
    @Scheduled(fixedDelayString = "${inventory.reservation.sweepIntervalMs:300000}",
               initialDelayString = "${inventory.reservation.sweepInitialDelayMs:60000}")
    public void sweep() {
        if (!enabled) return;
        try {
            int freed = sweepBatch(LocalDateTime.now());
            if (freed > 0) LOG.info("OMS O5a: released {} expired stock hold(s)", freed);
        } catch (RuntimeException e) {
            // A scheduled task that throws is silently unscheduled by some executors; this one must keep
            // running, because the thing it repairs only accumulates.
            LOG.error("OMS O5a: expiry sweep failed; will retry on the next tick", e);
        }
    }

    /** One bounded pass across all tenants. Returns how many holds were freed. */
    public int sweepBatch(LocalDateTime now) {
        List<Reservation> candidates =
                reservationRepository.findExpired(now, PageRequest.of(0, Math.max(1, batchSize)));
        int freed = 0;
        for (Reservation candidate : candidates) {
            if (worker.expireOne(candidate.getId(), now)) freed++;
        }
        return freed;
    }

    /** One tenant's expired holds, for the manual endpoint — an operator only ever frees their own. */
    public int sweepForOrg(LocalDateTime now, Long orgId, Long userId) {
        List<Reservation> candidates = reservationRepository.findExpiredScoped(
                now, orgId, userId, PageRequest.of(0, Math.max(1, batchSize)));
        int freed = 0;
        for (Reservation candidate : candidates) {
            if (worker.expireOne(candidate.getId(), now)) freed++;
        }
        return freed;
    }
}
