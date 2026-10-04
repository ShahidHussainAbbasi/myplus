package com.myplus.business_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.entity.Purchase;

/** MS-5 — a purchase being SAVED: PurchaseDTO → Purchase (was the MM-1 purchaseInput profile); oracle-tested. */
@Mapper
public interface PurchaseInputMapper extends InputDates {

    @Mapping(target = "batchNo", ignore = true)
    @Mapping(target = "bexpDate", ignore = true)
    @Mapping(target = "bpurchaseDiscount", ignore = true)
    @Mapping(target = "bpurchaseDiscountType", ignore = true)
    @Mapping(target = "bpurchaseRate", ignore = true)
    @Mapping(target = "bsellDiscount", ignore = true)
    @Mapping(target = "bsellDiscountType", ignore = true)
    @Mapping(target = "bsellRate", ignore = true)
    @Mapping(target = "docType", ignore = true)
    @Mapping(target = "dueAmount", ignore = true)
    @Mapping(target = "issuedTotal", ignore = true)
    @Mapping(target = "organizationId", ignore = true)
    @Mapping(target = "paidTotal", ignore = true)
    @Mapping(target = "storeId", ignore = true)
    @Mapping(target = "voidReason", ignore = true)
    @Mapping(target = "voidedAt", ignore = true)
    @Mapping(target = "voidedBy", ignore = true)
    Purchase toEntity(PurchaseDTO dto);
}
