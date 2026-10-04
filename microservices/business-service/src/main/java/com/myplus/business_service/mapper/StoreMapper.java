package com.myplus.business_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.myplus.business_service.dto.StoreDTO;
import com.myplus.business_service.entity.Store;

/**
 * MS-1 — Store → StoreDTO at compile time (docs/slices/ms-1-mapstruct-migration.md). Replaces the plain ModelMapper
 * profile for this pair; {@code MapStructOracleTest} proves the output is field-for-field the profile's.
 */
@Mapper
public interface StoreMapper {

    /** {@code active} is not a store fact: getMyStores sets it from the caller's active store. */
    @Mapping(target = "active", ignore = true)
    StoreDTO toDto(Store store);
}
