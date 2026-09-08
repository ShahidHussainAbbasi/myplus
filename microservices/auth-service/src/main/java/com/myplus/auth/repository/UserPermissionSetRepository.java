package com.myplus.auth.repository;

import com.myplus.auth.entity.UserPermissionSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserPermissionSetRepository extends JpaRepository<UserPermissionSet, Long> {

    Optional<UserPermissionSet> findByUserId(Long userId);

    /** Everyone on a set — what "you cannot delete a set somebody is using" is asked with. */
    List<UserPermissionSet> findBySetId(Long setId);
}
