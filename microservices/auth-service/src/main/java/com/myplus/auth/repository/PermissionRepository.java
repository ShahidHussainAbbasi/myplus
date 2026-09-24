package com.myplus.auth.repository;

import com.myplus.auth.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** The catalog. Read-only in practice — rows arrive by migration, never from a request. */
public interface PermissionRepository extends JpaRepository<Permission, String> {

    /** Every permission, in the order the matrix draws them. */
    List<Permission> findAllByOrderBySortOrderAsc();

    /**
     * The catalogue a tenant of this module may use: its own codes plus the COMMON ones.
     *
     * <p>⚠ Prefer this over {@link #findAllByOrderBySortOrderAsc()} for anything a TENANT sees or is
     * minted. The unfiltered read offers a school {@code sale.create}.
     */
    @Query("select p from Permission p where p.module = :module or p.module = 'COMMON' "
         + "order by p.sortOrder asc")
    List<Permission> findForModule(@Param("module") String module);
}
