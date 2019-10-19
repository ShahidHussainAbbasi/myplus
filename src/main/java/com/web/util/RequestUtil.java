package com.web.util;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

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
import com.service.IUserService;


@Component
public class RequestUtil {

    public static final Map<String,Object> userProperties = new HashMap<String,Object>();

	@Autowired
	IUserService userService;

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

    public static void loadUserProperties() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = null;
        if (authentication != null) {
            principal = authentication.getPrincipal();
        }
        userProperties.put("authorities", authentication.getAuthorities());
        if (principal != null && principal instanceof User) {
        	userProperties.put("user", (User)principal);
        }
    }
    
    public String getPath(String directory) throws UnsupportedEncodingException {
    	String path = this.getClass().getClassLoader().getResource("").getPath();
    	String fullPath = URLDecoder.decode(path, "UTF-8");
    	String pathArr[] = fullPath.split("/WEB-INF/classes/");
    	System.out.println(fullPath);
    	System.out.println(pathArr[0]);
    	fullPath = pathArr[0];
    	String reponsePath = "";
    	// to read a file from webcontent
    	reponsePath = new File(fullPath).getPath() + File.separatorChar + directory;
		File customDir = new File(reponsePath);
		if (customDir.exists()) {
		    System.out.println(customDir + " already exists");
		} else if (customDir.mkdirs()) {
		    System.out.println(customDir + " was created");
		} else {
		    System.out.println(customDir + " was not created");
		}			
    	
    	return reponsePath+"/";  
    }
}
