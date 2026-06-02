package com.myplus.business_service.service;

import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.dto.CustomerHistoryDTO;

public interface ICustomerService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Customer, Long> {

    Customer saveUpdateCustomer(CustomerHistoryDTO customerObj) throws Exception;


}
