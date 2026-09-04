package com.myplus.business_service.repository;

import com.myplus.business_service.entity.PlanGuarantor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** R4 — the guarantors on a plan, and the ones this shop has used before. Every read is org-scoped. */
@Repository
public interface PlanGuarantorRepo extends JpaRepository<PlanGuarantor, Long> {

    /**
     * One plan's guarantors, oldest first — the order they were entered, which is the order the agreement
     * will print them in.
     *
     * <p>Scoped by org as well as plan: the plan id arrives from the wire on
     * {@code /planGuarantors?planId=…}, and an id off the wire is not an id followed from a row the caller
     * could already see.
     */
    List<PlanGuarantor> findByOrganizationIdAndPlanIdOrderByIdAsc(Long organizationId, Long planId);

    /**
     * R4 — recall by CNIC. <b>Exact match only, and only within the caller's own organisation.</b>
     *
     * <h3>Why exact and not a prefix</h3>
     * A prefix search would let a member of staff type {@code 352} and walk a list of national identifiers.
     * A complete match cannot be walked — the caller already has to be holding the card, which is the same
     * trade-off as looking an order up by its full number rather than browsing everyone's.
     *
     * <p>Newest first, so a guarantor whose address has changed comes back as most recently recorded.
     *
     * <h3>Matched on DIGITS, not on the string as typed</h3>
     * A shop does not punctuate a CNIC the same way twice: 35201-1234567-8 today, 3520112345678 tomorrow. A
     * recall keyed on the raw string would look broken for exactly the person it was built to find, so both
     * sides are stripped to digits. Native because the stripping has to happen in the database — pulling
     * every guarantor back to compare them in Java is the N+1 this repository exists to avoid.
     *
     * <p>The caller enforces the 13-digit minimum; this query would happily match a short one.
     */
    @Query(value = "SELECT * FROM plan_guarantor "
                 + "WHERE organization_id = :orgId "
                 + "AND REGEXP_REPLACE(COALESCE(cnic,''), '[^0-9]', '') = :digits "
                 + "AND REGEXP_REPLACE(COALESCE(cnic,''), '[^0-9]', '') <> '' "
                 + "ORDER BY id DESC LIMIT 1", nativeQuery = true)
    List<PlanGuarantor> recallByNormalisedCnic(@Param("orgId") Long orgId, @Param("digits") String digits);

    /**
     * R4 — the people this shop uses most, for the one-tap recall chips.
     *
     * <p>Grouped in the DATABASE. A shop with hundreds of plans would otherwise load every guarantor row to
     * count them in Java, on a screen a cashier opens all day.
     *
     * <p>Returns {@code [name, cnic, contact, address, uses]} newest-and-most-used first. Rows without a CNIC
     * are still offered — a guarantor recorded by name alone is a real one.
     */
    @Query("SELECT g.name, g.cnic, g.contact, g.address, COUNT(g.id) AS uses, MAX(g.id) AS latest "
         + "FROM PlanGuarantor g WHERE g.organizationId = :orgId AND g.role = 'GUARANTOR' "
         + "GROUP BY g.name, g.cnic, g.contact, g.address "
         + "ORDER BY uses DESC, latest DESC")
    List<Object[]> recentForOrg(@Param("orgId") Long orgId);

    /** How many GUARANTOR rows a plan carries — the number {@code guarantorsRequired} is measured against. */
    @Query("SELECT COUNT(g) FROM PlanGuarantor g WHERE g.planId = :planId AND g.role = 'GUARANTOR'")
    long countGuarantorsForPlan(@Param("planId") Long planId);
}
