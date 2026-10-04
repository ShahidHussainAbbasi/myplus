package com.myplus.business_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.myplus.business_service.dto.CompanyDTO;
import com.myplus.business_service.entity.Company;

/** MS-2 — Company ⇄ CompanyDTO at compile time; MapStructOracleTest proves each direction equals the plain profile. */
@Mapper
public interface CompanyMapper {

    @Mapping(target = "datedStr", ignore = true)
    @Mapping(target = "updatedStr", ignore = true)
    CompanyDTO toDto(Company entity);

    @Mapping(target = "organizationId", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Company toEntity(CompanyDTO dto);
}
