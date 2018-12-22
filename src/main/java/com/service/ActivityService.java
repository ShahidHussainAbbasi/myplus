package com.service;

import java.util.List;

import org.springframework.data.domain.Page;

import com.persistence.model.Activity;
import com.persistence.model.User;

public interface ActivityService {
    Activity save(Activity activity);
    Activity findFirst();
    Activity findLast(User user);
    Page<Activity> findByUser(User user, int page, int size);
    Activity findOne(long id);

    List<Activity> findAll();

    void delete(Long id);
}