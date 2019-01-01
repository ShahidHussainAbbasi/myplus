package com.web.util;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.persistence.model.User;
import com.service.UserService;


@Component
public class RequestUtil {

	@Autowired
	UserService userService;

//    public RequestContext getRequestContext() {
//        HttpServletRequest request = getCurrentHttpRequest();
//
//        return new RequestContext()
//                .add("url", getRequestUrl(request))
//                .add("username", getRequestUserName());
//    }

    @Nullable
    public String getRequestUrl(@Nullable HttpServletRequest request) {
        return request == null ? null : request.getRequestURL().toString();
    }

    @Nullable
    public String getRequestUserName(@Nullable UserDetails userDetails) {
        return userDetails == null ? null : userDetails.getUsername();
    }

    @Nullable
    public String getRequestUserName() {
    	Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    	if (principal instanceof UserDetails) {
    		return ((UserDetails)principal).getUsername();
    	} else if(principal!=null && principal instanceof User){
			return principal.toString();
    	}  
    	return null;
    }

    @Nullable
    public User getRequestUser() {
    	this.getCurrentUser();
        return (User)userService.findUserByEmail(this.getRequestUserName());
    }

    @Nullable
    public HttpServletRequest getCurrentHttpRequest() {
        HttpServletRequest request = null;
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes != null && requestAttributes instanceof ServletRequestAttributes) {
            request = ((ServletRequestAttributes)requestAttributes).getRequest();
        }
        return request;
    }

    @Nullable
    public User getCurrentUser() {
        User user = null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = null;
        if (authentication != null) {
            principal = authentication.getPrincipal();
        }
        if (principal != null && principal instanceof User) {
            user = (User)principal;
        }
        return user;
    }

}
