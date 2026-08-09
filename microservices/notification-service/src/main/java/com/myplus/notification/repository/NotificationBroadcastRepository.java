package com.myplus.notification.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.myplus.notification.entity.NotificationBroadcast;

@Repository
public interface NotificationBroadcastRepository extends JpaRepository<NotificationBroadcast, Long> {

    /**
     * Find an earlier broadcast with the same idempotency key.
     *
     * <p>Used to ANSWER a duplicate rather than re-send it. The unique key is the guarantee; this lookup is
     * what lets the caller be told "already accepted, here it is" instead of receiving a constraint error
     * — the same division of labour the scheduling core settled on in SCHED-1.
     */
    Optional<NotificationBroadcast> findByDedupeKey(String dedupeKey);
}
