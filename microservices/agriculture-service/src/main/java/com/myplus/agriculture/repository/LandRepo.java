package com.myplus.agriculture.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.agriculture.entity.Land;

public interface LandRepo extends JpaRepository<Land, Long>, QueryByExampleExecutor<Land> {
}
