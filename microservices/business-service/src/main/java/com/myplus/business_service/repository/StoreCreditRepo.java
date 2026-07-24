package com.myplus.business_service.repository;

import com.myplus.business_service.entity.StoreCreditTxn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/** Store-credit ledger. The balance is the tenant-scoped sum of a customer's rows (+ issue, − redeem). */
@Repository
public interface StoreCreditRepo extends JpaRepository<StoreCreditTxn, Long> {

    @Query("select coalesce(sum(t.amount), 0) from StoreCreditTxn t where t.customerId = :cid "
         + "and (t.organizationId = :orgId or (t.organizationId is null and t.userId = :userId))")
    BigDecimal balanceScoped(@Param("cid") Long customerId, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("select t from StoreCreditTxn t where t.customerId = :cid "
         + "and (t.organizationId = :orgId or (t.organizationId is null and t.userId = :userId)) order by t.id desc")
    List<StoreCreditTxn> findByCustomerScoped(@Param("cid") Long customerId, @Param("orgId") Long orgId, @Param("userId") Long userId);
}
