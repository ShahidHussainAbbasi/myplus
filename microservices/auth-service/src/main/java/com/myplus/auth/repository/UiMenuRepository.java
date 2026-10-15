package com.myplus.auth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.auth.entity.UiMenu;

/** RW-0b — the menu catalogue (V25). */
public interface UiMenuRepository extends JpaRepository<UiMenu, Long> {

    List<UiMenu> findAllByOrderBySortOrderAsc();
}
