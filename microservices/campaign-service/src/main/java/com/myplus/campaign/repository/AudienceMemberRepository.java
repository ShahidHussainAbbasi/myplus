package com.myplus.campaign.repository;

import com.myplus.campaign.entity.AudienceMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AudienceMemberRepository extends JpaRepository<AudienceMember, Long> {
    Page<AudienceMember> findByAudienceId(Long audienceId, Pageable pageable);
    List<AudienceMember> findByEmail(String email);
    List<AudienceMember> findByAudienceIdAndIsActiveTrue(Long audienceId);
    long countByAudienceIdAndIsActiveTrue(Long audienceId);
}
