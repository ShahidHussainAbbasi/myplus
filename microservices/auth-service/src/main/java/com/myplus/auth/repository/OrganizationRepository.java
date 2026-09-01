package com.myplus.auth.repository;

import com.myplus.auth.entity.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    List<Organization> findByOwnerUserId(Long ownerUserId);
    List<Organization> findByParentId(Long parentId);

    /**
     * E2 — the operator's tenant search. <b>The platform's first deliberate cross-tenant read.</b>
     *
     * <p>Everything else in this codebase scopes by organization; this query's whole purpose is not to, which
     * is why the only thing standing between it and a customer is {@code ROLE_ADMIN} on the controller. It is
     * a repository method, not a service shortcut, precisely so that anyone grepping for unscoped reads finds
     * it and finds this comment.
     *
     * <p><b>Paged in the database, not in Java.</b> 40 tenants today; the query is written for 40,000. A
     * {@code findAll()} followed by a stream filter would work for a year and then stop working on the day it
     * matters, and the fix would be a rewrite of everything built on top of it.
     *
     * <p><b>One query for both "search" and "list everything"</b>, because two methods would drift into two
     * orderings the day somebody changed one. The blank case passes the pattern {@code %} rather than a
     * {@code :q IS NULL} branch: a null-comparison on a bound parameter is a well-known Hibernate 6 footgun
     * (the parameter's type cannot be inferred from {@code IS NULL} alone), and it fails at runtime on the
     * first call rather than at compile time. The caller normalises, so this stays a plain LIKE.
     */
    @Query("SELECT o FROM Organization o WHERE LOWER(o.name) LIKE :q")
    Page<Organization> searchForOperator(@Param("q") String q, Pageable pageable);
}
