package com.myplus.marketplace.multiseller.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplacePlatformSetting;

public interface MarketplacePlatformSettingRepository extends JpaRepository<MarketplacePlatformSetting, String> {
}
