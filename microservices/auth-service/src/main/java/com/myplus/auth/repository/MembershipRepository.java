package com.myplus.auth.repository;

import com.myplus.auth.entity.Membership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MembershipRepository extends JpaRepository<Membership, Long> {
    List<Membership> findByUserId(Long userId);
    List<Membership> findByOrganizationId(Long organizationId);
    Optional<Membership> findByUserIdAndOrganizationId(Long userId, Long organizationId);
}
