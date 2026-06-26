package com.web.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

import com.persistence.model.User;

/**
 * The single commerce dashboard (slice 36). ONE template ({@code businessDashboard.html}) on ONE route
 * ({@code /businessDashboard}) serves every commerce vertical — POS ({@code BUSINESS}), Pharmacy ({@code PHARMA})
 * and Store ({@code ECOMMERCE}) — white-labelled at runtime. This controller sets {@code module} from the logged-in
 * user's type; {@code module-theme.js} then applies that vertical's profile (terminology / features / theme).
 * No per-vertical routes: all commerce types land here.
 */
@Controller
public class CommerceDashboardController {

    /** Commerce verticals that share this dashboard; anything else falls back to the POS wording. */
    private static final java.util.Set<String> COMMERCE_MODULES = java.util.Set.of("BUSINESS", "PHARMA", "MARKETPLACE");

    @GetMapping("/businessDashboard")
    public ModelAndView businessDashboard() {
        ModelAndView mav = new ModelAndView("businessDashboard");
        mav.addObject("module", resolveModule());
        return mav;
    }

    /** The active vertical = the logged-in user's type, when it is a commerce vertical; else POS (BUSINESS). */
    private String resolveModule() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            Object principal = auth != null ? auth.getPrincipal() : null;
            if (principal instanceof User user && user.getUserType() != null) {
                String type = user.getUserType().toUpperCase();
                if (COMMERCE_MODULES.contains(type)) return type;
            }
        } catch (Exception ignore) {
            // fall through to the default
        }
        return "BUSINESS";
    }
}
