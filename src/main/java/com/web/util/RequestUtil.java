package com.web.util;

import javax.servlet.http.HttpServletRequest;

import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class RequestUtil {

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
    private String getRequestUserName() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
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
    public UserDetails getCurrentUser() {
        UserDetails user = null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = null;
        if (authentication != null) {
            principal = authentication.getPrincipal();
        }
        if (principal != null && principal instanceof UserDetails) {
            user = (UserDetails)principal;
        }
        return user;
    }

}
