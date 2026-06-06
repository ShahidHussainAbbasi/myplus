package com.myplus.auth.repository;

import com.myplus.auth.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    List<Organization> findByOwnerUserId(Long ownerUserId);
    List<Organization> findByParentId(Long parentId);
}
