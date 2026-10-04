package com.myplus.business_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.entity.CustomerHistory;

/**
 * MS-6 — the sale's invoice header, started from the customer details the sale posted (was ObjectMapperUtils, a static
 * STRICT ModelMapper). CustomerHistoryService then stamps who, which tenant, when, the amounts and the number; the
 * header's customer link is set by the sale path itself. MapStructOracleTest holds this to the STRICT oracle.
 */
@Mapper
public interface SaleHeaderMapper {

    @Mapping(target = "balanceAfter", ignore = true)
    @Mapping(target = "bookedByName", ignore = true)
    @Mapping(target = "changeAmount", ignore = true)
    @Mapping(target = "customer", ignore = true)
    @Mapping(target = "customerPoNumber", ignore = true)
    @Mapping(target = "customer_history_id", ignore = true)
    @Mapping(target = "docType", ignore = true)
    @Mapping(target = "grandTotal", ignore = true)
    @Mapping(target = "idempotencyKey", ignore = true)
    @Mapping(target = "invoiceNo", ignore = true)
    @Mapping(target = "invoiceSeq", ignore = true)
    @Mapping(target = "issuedTotal", ignore = true)
    @Mapping(target = "orderType", ignore = true)
    @Mapping(target = "organizationId", ignore = true)
    @Mapping(target = "paymentMode", ignore = true)
    @Mapping(target = "reservationId", ignore = true)
    @Mapping(target = "sagaStatus", ignore = true)
    @Mapping(target = "shiftId", ignore = true)
    @Mapping(target = "shippingFee", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "storeId", ignore = true)
    @Mapping(target = "subTotal", ignore = true)
    @Mapping(target = "taxTotal", ignore = true)
    @Mapping(target = "tenderedAmount", ignore = true)
    @Mapping(target = "tradeDiscount", ignore = true)
    @Mapping(target = "voidReason", ignore = true)
    @Mapping(target = "voidedAt", ignore = true)
    @Mapping(target = "voidedBy", ignore = true)
    @Mapping(target = "dated", ignore = true)
    @Mapping(target = "updated", ignore = true)
    CustomerHistory fromCustomer(CustomerDTO customer);
}
