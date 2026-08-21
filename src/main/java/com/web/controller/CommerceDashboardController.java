package com.web.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

import com.persistence.model.User;
import com.web.util.ModuleRouter;

/**
 * The single commerce dashboard (slice 36). ONE template ({@code businessDashboard.html}) on ONE route
 * ({@code /businessDashboard}) serves every commerce vertical — POS ({@code BUSINESS}), Pharmacy ({@code PHARMA})
 * and Store ({@code MARKETPLACE}) — white-labelled at runtime. This controller sets {@code module};
 * {@code module-theme.js} then applies that vertical's profile (terminology / features / theme).
 * No per-vertical routes: all commerce types land here.
 *
 * <h3>INST-0b — the skin now follows the same rule as the routing</h3>
 * This class used to resolve the vertical from {@code user.getUserType()} alone, against its own private copy of
 * the commerce-type set. {@link ModuleRouter} — extracted in B2B P0.5 precisely to end a duplicated
 * type&rarr;screen rule — had meanwhile moved to preferring the <b>active organization's</b> type, with
 * {@code userType} only as the fallback for tenants predating the column.
 *
 * <p>So the two disagreed: a {@code BUSINESS}-typed user working inside a {@code PHARMA} org was <b>routed</b>
 * here by {@code ModuleRouter} and then <b>skinned as POS</b> by this method — a Pharmacy tenant whose screens
 * said "Customer" and "Item". Deferring to {@link ModuleRouter#moduleOf} removes the second copy rather than
 * updating it, which is the whole reason that class exists.
 *
 * <p>Behaviour is unchanged for every user whose org type is null or equals their user type — which is every
 * single-module tenant, i.e. almost all of them.
 */
@Controller
public class CommerceDashboardController {

    @GetMapping("/businessDashboard")
    public ModelAndView businessDashboard() {
        ModelAndView mav = new ModelAndView("businessDashboard");
        mav.addObject("module", resolveModule());
        return mav;
    }

    /**
     * The active vertical: the module the user is actually working in (active org, else their own type) when
     * that module is a commerce vertical; otherwise POS wording.
     *
     * <p>The non-commerce fallback is deliberate and unchanged: an EDUCATION user who reaches this URL directly
     * gets a working POS-worded screen rather than a half-relabelled one. Routing is not authorization — every
     * section on the page is still gated by privilege and org scope.
     */
    private String resolveModule() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            Object principal = auth != null ? auth.getPrincipal() : null;
            if (principal instanceof User user) {
                String module = ModuleRouter.moduleOf(user);
                if (ModuleRouter.isCommerce(module)) return module;
            }
        } catch (Exception ignore) {
            // fall through to the default
        }
        return "BUSINESS";
    }
}
