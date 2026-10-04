package com.myplus.business_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.myplus.business_service.dto.VenderDTO;
import com.myplus.business_service.entity.Vender;

/** MS-2 — Vender ⇄ VenderDTO at compile time; MapStructOracleTest proves each direction equals the plain profile. */
@Mapper
public interface VenderMapper {

    // ModelMapper matched this IMPLICITLY (STANDARD tokens: "advance" in "payableAdvance"); found by the MS-2 oracle and
    // kept so nothing changes. getUserVender then overwrites it by payables source (finance figure, or 0 on BUSINESS);
    // getAllVender does not — recorded in docs/slices/ms-1-mapstruct-migration.md §6.
    @Mapping(target = "advance", source = "payableAdvance")
    @Mapping(target = "alsoCustomer", ignore = true)
    @Mapping(target = "billsOwed", ignore = true)
    @Mapping(target = "companyId", ignore = true)
    @Mapping(target = "companyIds", ignore = true)
    @Mapping(target = "companyNames", ignore = true)
    @Mapping(target = "datedStr", ignore = true)
    @Mapping(target = "payablesSource", ignore = true)
    @Mapping(target = "totalOwed", ignore = true)
    @Mapping(target = "updatedStr", ignore = true)
    VenderDTO toDto(Vender entity);

    @Mapping(target = "companies", ignore = true)
    @Mapping(target = "dated", ignore = true)
    @Mapping(target = "organizationId", ignore = true)
    @Mapping(target = "payableAdvance", ignore = true)
    @Mapping(target = "payableOtherOpen", ignore = true)
    @Mapping(target = "payableStampVersion", ignore = true)
    @Mapping(target = "updated", ignore = true)
    Vender toEntity(VenderDTO dto);
}
