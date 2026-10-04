package com.myplus.business_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.entity.Purchase;

/** MS-3 — Purchase → PurchaseDTO for the Purchase screens (was the MM-1 display profile); oracle-tested. */
@Mapper
public interface PurchaseMapper extends DisplayDates {

    @Mapping(target = "conditionGrade", ignore = true)
    @Mapping(target = "creditAcknowledged", ignore = true)
    @Mapping(target = "duplicateBillAcknowledged", ignore = true)
    @Mapping(target = "icode", ignore = true)
    @Mapping(target = "idempotencyKey", ignore = true)
    @Mapping(target = "iname", ignore = true)
    @Mapping(target = "packsPerBox", ignore = true)
    @Mapping(target = "pstockId", ignore = true)
    @Mapping(target = "purchaseUnit", ignore = true)
    @Mapping(target = "serials", ignore = true)
    @Mapping(target = "serialsSubmitted", ignore = true)
    @Mapping(target = "stock", ignore = true)
    @Mapping(target = "venderName", ignore = true)
    PurchaseDTO toDto(Purchase entity);
}
