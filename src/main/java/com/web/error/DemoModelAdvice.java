package com.web.error;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.persistence.model.User;

/**
 * Exposes {@code demoUser} to every server-rendered view so templates can show the demo banner without
 * a fragile principal-type SpEL check ({@code th:if="${demoUser}"}). True only for a demo session.
 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class DemoModelAdvice {

    @ModelAttribute("demoUser")
    public boolean demoUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof User u && u.isDemo();
    }
}
