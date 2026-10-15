package com.myplus.auth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.auth.entity.UiWorkspace;

/** RW-3 — the workspaces (V27). */
public interface UiWorkspaceRepository extends JpaRepository<UiWorkspace, Long> {

    List<UiWorkspace> findAllByOrderBySortOrderAsc();
}
