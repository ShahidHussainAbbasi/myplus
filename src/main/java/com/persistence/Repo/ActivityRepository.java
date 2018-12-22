package com.persistence.Repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.persistence.model.Activity;
import com.persistence.model.User;

@Repository
public interface ActivityRepository extends JpaRepository<Activity,Long> {
    Activity findFirstBy();
    Activity findFirstByUserOrderByIdDesc(User user);
    Page<Activity> findByUser(User user, Pageable pageable);
}