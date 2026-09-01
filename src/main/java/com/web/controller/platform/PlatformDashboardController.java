package com.web.controller.platform;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * E2 — the platform operator's console.
 *
 * <h3>Why this is a page of its own and not a section of businessDashboard</h3>
 * {@code businessDashboard.html} is 3,947 lines and 36 {@code .formDiv} sections, shipped whole to every
 * tenant. Putting a surface that lists <b>every customer</b> inside a page every customer loads is one CSS
 * mistake away from a shopkeeper seeing the tenant list. Separate page, separate route, separate gate.
 *
 * <h3>A controller, not a view controller</h3>
 * {@code MvcConfig.addViewControllers} registers static routes with no gate at all — which is right for
 * {@code /registration.html} and wrong for this. A {@code @PreAuthorize} here means an unauthorised GET is
 * refused before the template is ever resolved.
 *
 * <p><b>ROLE_ADMIN, never ADMIN_PRIVILEGE.</b> Every tenant owner holds {@code ADMIN_PRIVILEGE} inside their
 * own organization. And this gate stops the PAGE rendering only: every endpoint the page calls is
 * independently gated in auth-service, because hiding a screen has never been a control.
 */
@Controller
public class PlatformDashboardController {

    @GetMapping("/platformDashboard")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ModelAndView platformDashboard() {
        return new ModelAndView("platformDashboard");
    }
}
