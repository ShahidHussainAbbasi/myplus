package com.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.ActivityRepository;
import com.persistence.model.Activity;
import com.persistence.model.User;

@Service
public class ActivityServiceImpl implements ActivityService {
    private final ActivityRepository activityRepo;

    @Autowired
    public ActivityServiceImpl(ActivityRepository activityRepo) {
        this.activityRepo = activityRepo;
    }

    public Activity save(Activity activity) {
        if (activity.getId() == null) { // new activity (user logged in)
            Activity firstActivity = this.findFirst();
            if (firstActivity != null) {
            	if(firstActivity.getTotalVisitors()==null)
            		firstActivity.setTotalVisitors(0L);
            	
                long total = firstActivity.getTotalVisitors();
                activity.setTotalVisitors(++total);
                firstActivity.setTotalVisitors(total);
                this.activityRepo.save(firstActivity);
            }
        }
        return this.activityRepo.save(activity);
    }

    @Override
    public Activity findFirst() {
        return this.activityRepo.findFirstBy();
    }

    @Override
    public Activity findLast(User user) {
        return activityRepo.findFirstByUserOrderByIdDesc(user);
    }

    @SuppressWarnings("deprecation")
	@Override
    public Page<Activity> findByUser(User user, int page, int size) {
        return this.activityRepo.findByUser(user, new PageRequest(page, size, Sort.Direction.DESC, "id"));
    }

    @Override
    public Activity findOne(long id) {
        return null;//this.activityRepo.findOne(id);
    }

    @Override
    public List<Activity> findAll() {
        return this.activityRepo.findAll();
    }

    @Override
    public void delete(Long id) {
    	System.out.print(id);
//        this.activityRepo.delete(id);
    }

}
