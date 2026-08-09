package com.myplus.notification.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.myplus.notification.entity.DeliveryStatus;
import com.myplus.notification.entity.NotificationDelivery;

@Repository
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    /**
     * THE dispatcher query: what still needs sending, oldest first, capped.
     *
     * <p>Capped deliberately. A school broadcasting to 3,000 families creates 3,000 rows, and a relay that
     * loaded them all into one transaction would hold a connection for the length of 3,000 SMTP round
     * trips — the shape the platform's performance standard exists to prevent. Successive passes drain it.
     *
     * <p>Attempts are bounded by the caller, not here: a permanently bad address must stop being retried
     * or it occupies the queue forever.
     */
    @Query("select d from NotificationDelivery d where d.status = :status and d.attempts < :maxAttempts "
            + "order by d.id")
    List<NotificationDelivery> findDueForDispatch(@Param("status") DeliveryStatus status,
                                                  @Param("maxAttempts") int maxAttempts,
                                                  org.springframework.data.domain.Pageable page);

    /** Every recipient of one broadcast — "sent to 298, failed 2, here are the 2". */
    List<NotificationDelivery> findByBroadcastIdOrderByIdAsc(Long broadcastId);

    /**
     * What this person was sent, newest first — the support question.
     *
     * <p>Org-scoped as well as recipient-scoped: an address is not unique across tenants (a parent may be
     * a guardian at two schools on this platform), and answering with another tenant's rows would be a
     * cross-tenant disclosure through a support screen.
     */
    @Query("select d from NotificationDelivery d where d.organizationId = :orgId "
            + "and d.recipient = :recipient order by d.id desc")
    List<NotificationDelivery> findForRecipient(@Param("recipient") String recipient,
                                                @Param("orgId") Long orgId,
                                                org.springframework.data.domain.Pageable page);
}
