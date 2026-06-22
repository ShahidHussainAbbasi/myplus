package com.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * Pharmacy dashboard (slice 33 — pharmacy reuses catalog/inventory/trade). Pharmacy's commerce core
 * (medicine/item master, supplier, purchase, dispense/sale, stock incl. batch + expiry, customer) is the
 * same trade engine as the business dashboard, so we render the SAME {@code businessDashboard} view rather
 * than forking ~3.7k lines of HTML/JS. The {@code module=PHARMACY} attribute drives terminology/branding
 * client-side (module-theme.js). Pharmacy-specific clinical/Rx screens are an additive layer on top
 * (pharma-service), surfaced as extra nav later — not a reason to duplicate the POS.
 */
@Controller
public class PharmacyDashboardController {

    @GetMapping("/pharmacyDashboard")
    public ModelAndView pharmacyDashboard() {
        ModelAndView mav = new ModelAndView("businessDashboard");
        mav.addObject("module", "PHARMACY");
        return mav;
    }
}
