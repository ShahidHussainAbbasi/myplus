package com.myplus.welfare.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.welfare.entity.Donation;

public interface DonationRepo extends JpaRepository<Donation, Long>, QueryByExampleExecutor<Donation> {
}
