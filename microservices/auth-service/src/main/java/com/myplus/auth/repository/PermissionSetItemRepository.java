package com.myplus.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The set's contents. A join table with no entity of its own — it carries no data beyond the pair, and
 * an @Entity for it would only add a surrogate key nothing reads.
 */
@Repository
public interface PermissionSetItemRepository extends JpaRepository<com.myplus.auth.entity.Permission, String> {

    @Query(value = "select permission_code from permission_set_item where set_id = :setId", nativeQuery = true)
    List<String> codesOf(@Param("setId") Long setId);

    @Modifying
    @Query(value = "delete from permission_set_item where set_id = :setId", nativeQuery = true)
    void clear(@Param("setId") Long setId);

    @Modifying
    @Query(value = "insert into permission_set_item (set_id, permission_code) values (:setId, :code)",
           nativeQuery = true)
    void add(@Param("setId") Long setId, @Param("code") String code);
}
