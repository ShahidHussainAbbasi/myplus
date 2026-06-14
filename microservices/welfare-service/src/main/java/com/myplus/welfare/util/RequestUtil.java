package com.myplus.welfare.util;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.myplus.common.security.AuthenticatedUser;

@Component
public class RequestUtil {

    @Nullable
    public HttpServletRequest getCurrentHttpRequest() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes instanceof ServletRequestAttributes) {
            return ((ServletRequestAttributes) requestAttributes).getRequest();
        }
        return null;
    }

    @Nullable
    public AuthenticatedUser getCurrentUser() {
        // Primary: read from SecurityContextHolder
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser) {
            return (AuthenticatedUser) authentication.getPrincipal();
        }
        // Fallback: read from request attribute set by HeaderAuthFilter
        HttpServletRequest request = getCurrentHttpRequest();
        if (request != null) {
            Object attr = request.getAttribute("_authenticated_user");
            if (attr instanceof AuthenticatedUser) {
                return (AuthenticatedUser) attr;
            }
        }
        return null;
    }
}
