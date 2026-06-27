package com.myplus.business_service.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.business_service.dto.ParkSaleDTO;
import com.myplus.business_service.service.ParkedSaleService;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

/**
 * Park / hold & resume a sale (POS R10, slice 40). org/cashier from the propagated gateway identity; all reads/
 * writes are scoped to that cashier (anti-IDOR).
 */
@Controller
public class ParkedSaleController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired ParkedSaleService parkedSaleService;
    @Autowired RequestUtil requestUtil;

    private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u == null ? null : u.getUserId(); }
    private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u == null ? null : u.getOrganizationId(); }

    @RequestMapping(value = "/parkSale", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse parkSale(@RequestBody ParkSaleDTO dto) {
        try {
            return new GenericResponse("SUCCESS", "Sale parked.", parkedSaleService.park(dto, orgId(), userId()).getId());
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > parkSale " + e.getMessage(), e);
            return new GenericResponse("FAILED", "Could not park the sale.");
        }
    }

    @RequestMapping(value = "/parkedSales", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse parkedSales() {
        try {
            return new GenericResponse("SUCCESS", null, parkedSaleService.list(orgId(), userId()));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > parkedSales " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not load parked sales.");
        }
    }

    @RequestMapping(value = "/resumeParked", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse resumeParked(@RequestParam("id") Long id) {
        try {
            return new GenericResponse("SUCCESS", null, parkedSaleService.resume(id, orgId(), userId()));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > resumeParked " + e.getMessage(), e);
            return new GenericResponse("NOT_FOUND", "Parked sale not found.");
        }
    }

    @RequestMapping(value = "/deleteParked", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse deleteParked(@RequestParam("id") Long id) {
        try {
            parkedSaleService.discard(id, orgId(), userId());
            return new GenericResponse("SUCCESS", "Parked sale discarded.");
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > deleteParked " + e.getMessage(), e);
            return new GenericResponse("FAILED", "Could not discard the parked sale.");
        }
    }
}
