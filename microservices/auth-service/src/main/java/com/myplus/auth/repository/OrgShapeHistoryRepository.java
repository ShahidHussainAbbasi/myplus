package com.myplus.auth.repository;

import com.myplus.auth.entity.OrgShapeHistory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** ONB-3 — one tenant's business-type changes, newest first. The only read this table has. */
public interface OrgShapeHistoryRepository extends JpaRepository<OrgShapeHistory, Long> {

    List<OrgShapeHistory> findByOrganizationIdOrderByChangedAtDesc(Long organizationId);
}
