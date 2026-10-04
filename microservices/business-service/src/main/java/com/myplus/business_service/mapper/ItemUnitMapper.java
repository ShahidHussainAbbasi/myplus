package com.myplus.business_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.myplus.business_service.dto.ItemUnitDTO;
import com.myplus.business_service.entity.ItemUnit;

/** MS-2 — ItemUnit ⇄ ItemUnitDTO at compile time; MapStructOracleTest proves each direction equals the plain profile. */
@Mapper
public interface ItemUnitMapper {

    @Mapping(target = "datedStr", ignore = true)
    @Mapping(target = "updatedStr", ignore = true)
    ItemUnitDTO toDto(ItemUnit entity);

    @Mapping(target = "dated", ignore = true)
    @Mapping(target = "organizationId", ignore = true)
    @Mapping(target = "updated", ignore = true)
    ItemUnit toEntity(ItemUnitDTO dto);
}
