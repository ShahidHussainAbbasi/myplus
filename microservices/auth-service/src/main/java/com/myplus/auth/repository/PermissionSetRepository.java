package com.myplus.auth.repository;

import com.myplus.auth.entity.PermissionSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PermissionSetRepository extends JpaRepository<PermissionSet, Long> {

    /**
     * The sets this tenant may use: its own, plus the built-in templates.
     *
     * <p>Scoped by organisation with the built-ins folded in, rather than two calls the caller must
     * remember to merge — a caller who forgets one gets a screen missing half its options and no error.
     */
    @Query("select s from PermissionSet s where s.organizationId = :orgId or s.organizationId is null "
         + "order by s.builtin desc, s.name asc")
    List<PermissionSet> findVisibleTo(@Param("orgId") Long orgId);

    /**
     * The same list, narrowed to one module — what a tenant actually chooses from.
     *
     * <p>Built-ins carry {@code organization_id IS NULL}, so without the module filter EVERY built-in
     * is visible to everyone: a school would be offered Cashier and Storekeeper, and a shop would be
     * offered Teacher and Principal.
     */
    @Query("select s from PermissionSet s where (s.organizationId = :orgId or s.organizationId is null) "
         + "and s.module = :module order by s.builtin desc, s.name asc")
    List<PermissionSet> findVisibleTo(@Param("orgId") Long orgId, @Param("module") String module);

    Optional<PermissionSet> findByOrganizationIdIsNullAndName(String name);

    /** ⚠ Scoped: a set id from another tenant must resolve to nothing, never to that tenant's set. */
    @Query("select s from PermissionSet s where s.id = :id "
         + "and (s.organizationId = :orgId or s.organizationId is null)")
    Optional<PermissionSet> findScoped(@Param("id") Long id, @Param("orgId") Long orgId);
}
