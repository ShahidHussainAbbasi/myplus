package com.myplus.business_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.entity.Customer;

/** MS-3 — Customer ⇄ CustomerDTO for the Customer screens (was the MM-1 display profile); oracle-tested. */
@Mapper
public interface CustomerMapper extends DisplayDates {

    @Mapping(target = "alsoSupplier", ignore = true)
    @Mapping(target = "customerHistory", ignore = true)
    @Mapping(target = "paidAmount", ignore = true)
    CustomerDTO toDto(Customer entity);

    @Mapping(target = "assignedRepUserId", ignore = true)
    @Mapping(target = "creditAccountCustomerId", ignore = true)
    @Mapping(target = "organizationId", ignore = true)
    @Mapping(target = "dated", ignore = true)
    @Mapping(target = "updated", ignore = true)
    Customer toEntity(CustomerDTO dto);
}
