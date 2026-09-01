package com.myplus.auth.repository;

import com.myplus.auth.entity.Membership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MembershipRepository extends JpaRepository<Membership, Long> {
    List<Membership> findByUserId(Long userId);
    List<Membership> findByOrganizationId(Long organizationId);
    Optional<Membership> findByUserIdAndOrganizationId(Long userId, Long organizationId);

    /**
     * E2 — member counts for a whole PAGE of organizations, in one query.
     *
     * <p>Exists to avoid the obvious N+1: the operator's tenant list shows a member count per row, and calling
     * {@link #findByOrganizationId} once per row would issue 25 queries per page and load every membership row
     * only to call {@code size()} on it. This counts in the database and returns one tuple per org.
     *
     * <p>Returns {@code Object[]{organizationId, count}} rather than a projection interface because the two
     * callers are one, and a named projection for a pair of longs is ceremony. The service turns it into a map
     * immediately.
     */
    @Query("SELECT m.organizationId, COUNT(m) FROM Membership m "
            + "WHERE m.organizationId IN :organizationIds GROUP BY m.organizationId")
    List<Object[]> countByOrganizationIds(@Param("organizationIds") Collection<Long> organizationIds);
}
