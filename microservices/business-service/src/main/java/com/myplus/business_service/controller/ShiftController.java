package com.myplus.business_service.controller;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.business_service.entity.MovementType;
import com.myplus.business_service.service.ShiftService;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

/**
 * POS day-close (slice 39): cashier shift + cash drawer + X/Z report. org/user come from the propagated gateway
 * identity (the cashier). One open shift per cashier.
 */
@Controller
public class ShiftController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired ShiftService shiftService;
    @Autowired RequestUtil requestUtil;

    private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u == null ? null : u.getUserId(); }
    private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u == null ? null : u.getOrganizationId(); }

    @RequestMapping(value = "/openShift", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse openShift(@RequestParam(value = "openingFloat", required = false) BigDecimal openingFloat) {
        try {
            return new GenericResponse("SUCCESS", "Shift opened.", shiftService.openShift(openingFloat, orgId(), userId()));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > openShift " + e.getMessage(), e);
            return new GenericResponse("FAILED", e.getMessage());
        }
    }

    @RequestMapping(value = "/currentShift", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse currentShift() {
        try {
            return shiftService.currentOpenShift(orgId(), userId())
                    .map(s -> new GenericResponse("SUCCESS", null, s))
                    .orElseGet(() -> new GenericResponse("NOT_FOUND", "No open shift."));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > currentShift " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not load the shift.");
        }
    }

    @RequestMapping(value = "/cashMovement", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse cashMovement(@RequestParam("type") String type,
                                        @RequestParam("amount") BigDecimal amount,
                                        @RequestParam(value = "reason", required = false) String reason) {
        try {
            MovementType mt;
            try { mt = MovementType.valueOf(type == null ? "" : type.trim().toUpperCase()); }
            catch (Exception ex) { return new GenericResponse("FAILED", "Invalid movement type."); }
            return new GenericResponse("SUCCESS", "Cash movement recorded.",
                    shiftService.addCashMovement(mt, amount, reason, orgId(), userId()));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > cashMovement " + e.getMessage(), e);
            return new GenericResponse("FAILED", e.getMessage());
        }
    }

    @RequestMapping(value = "/shiftReport", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse shiftReport() {
        try {
            return new GenericResponse("SUCCESS", null, shiftService.reportX(orgId(), userId()));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > shiftReport " + e.getMessage(), e);
            return new GenericResponse("FAILED", e.getMessage());
        }
    }

    @RequestMapping(value = "/closeShift", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse closeShift(@RequestParam(value = "countedCash", required = false) BigDecimal countedCash,
                                      @RequestParam(value = "notes", required = false) String notes) {
        try {
            return new GenericResponse("SUCCESS", "Shift closed.", shiftService.closeShift(countedCash, notes, orgId(), userId()));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > closeShift " + e.getMessage(), e);
            return new GenericResponse("FAILED", e.getMessage());
        }
    }
}
