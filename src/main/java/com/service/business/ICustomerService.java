package com.service.business;

import com.persistence.Repo.business.CustomerRepo;
import com.persistence.model.business.Customer;
import com.web.dto.business.CustomerHistoryDTO;

public interface ICustomerService extends CustomerRepo {

    Customer saveUpdateCustomer(CustomerHistoryDTO customerObj) throws Exception;


}
