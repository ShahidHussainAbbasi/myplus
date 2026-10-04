package com.myplus.business_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.myplus.business_service.dto.ItemTypeDTO;
import com.myplus.business_service.entity.ItemType;

/** MS-2 — ItemType ⇄ ItemTypeDTO at compile time; MapStructOracleTest proves each direction equals the plain profile. */
@Mapper
public interface ItemTypeMapper {

    @Mapping(target = "datedStr", ignore = true)
    @Mapping(target = "updatedStr", ignore = true)
    ItemTypeDTO toDto(ItemType entity);

    @Mapping(target = "dated", ignore = true)
    @Mapping(target = "organizationId", ignore = true)
    @Mapping(target = "updated", ignore = true)
    ItemType toEntity(ItemTypeDTO dto);
}
