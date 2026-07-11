package com.myplus.business_service.service;

import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.dto.CustomerHistoryDTO;

public interface ICustomerHistoryService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.CustomerHistory, Long> {

    CustomerHistory saveUpdateCustomerHistory(CustomerHistoryDTO dto);

    /** Audit #3: resolve an invoice by its per-org invoice number (Void by invoiceNo). */
    java.util.Optional<CustomerHistory> findByOrgAndInvoiceNo(Long organizationId, String invoiceNo);

// Here DSL queries can be tried
//	void updateItemStock(PurchaseDTO dto);


}
