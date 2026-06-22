package com.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * Pharmacy dashboard (slice 33 — the pharmacy vertical reuses catalog/inventory/trade). Its commerce core
 * (medicine/item master, supplier, purchase, dispense/sale, stock incl. batch + expiry, customer) is the
 * same trade engine as the business dashboard, so we render the SAME {@code businessDashboard} view rather
 * than forking ~3.7k lines of HTML/JS. The {@code module=PHARMA} attribute drives terminology/branding
 * client-side (module-theme.js) — the internal identifier is PHARMA (matching pharma-service), while the
 * display reads "Pharmacy". Clinical/Rx screens are an additive pharma-service layer surfaced later.
 */
@Controller
public class PharmaDashboardController {

    @GetMapping("/pharmaDashboard")
    public ModelAndView pharmaDashboard() {
        ModelAndView mav = new ModelAndView("businessDashboard");
        mav.addObject("module", "PHARMA");
        return mav;
    }
}
