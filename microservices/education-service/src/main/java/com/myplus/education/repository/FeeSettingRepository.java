package com.myplus.education.repository;

import com.myplus.education.entity.FeeSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FeeSettingRepository extends JpaRepository<FeeSetting, Long> {
    Optional<FeeSetting> findByOrganizationId(Long organizationId);
}
