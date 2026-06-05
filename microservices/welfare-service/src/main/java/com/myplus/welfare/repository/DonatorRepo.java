package com.myplus.welfare.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.welfare.entity.Donator;

public interface DonatorRepo extends JpaRepository<Donator, Long>, QueryByExampleExecutor<Donator> {
}
