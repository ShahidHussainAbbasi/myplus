package com.myplus.education.repository;

import com.myplus.education.entity.FeeCreditTxn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface FeeCreditTxnRepository extends JpaRepository<FeeCreditTxn, Long> {

    /** A student's balance = the sum of their signed movements, within one tenant. COALESCE so a student with no
     *  history reads as 0 rather than null. */
    @Query("select coalesce(sum(t.amount), 0) from FeeCreditTxn t "
            + "where t.studentId = :studentId and t.organizationId = :orgId")
    BigDecimal balanceScoped(@Param("studentId") Long studentId, @Param("orgId") Long orgId);
}
