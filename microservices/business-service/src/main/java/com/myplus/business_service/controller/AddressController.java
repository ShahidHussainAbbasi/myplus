package com.myplus.business_service.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.business_service.service.AddressService;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

/**
 * SRA-1 — a business's cities and areas, for the inline address form on the customer and supplier screens.
 * Design: {@code microservices/docs/slices/sra-sales-by-rep-and-area.md} §4.
 *
 * <p><b>Reading</b> is open to every member: the pickers on the customer and supplier forms need it, and a list of
 * place names is not a financial read. <b>Adding</b> is the {@code area.create} permission — a DATA rule, mapped in
 * auth-service's {@code endpoint_permission} (V31) and enforced by the monolith's PermissionInterceptor, like every
 * other write; nothing here names a role.
 *
 * <p>Answers: SUCCESS (made now), FOUND (already there — the row is returned so the form can select it),
 * FAILED (a reason a person can act on).
 */
@Controller
public class AddressController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired private AddressService addresses;
    @Autowired private RequestUtil requestUtil;

    private AuthenticatedUser me() { return requestUtil.getCurrentUser(); }

    @RequestMapping(value = "/getCities", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getCities() {
        AuthenticatedUser u = me();
        if (u == null || u.getOrganizationId() == null) return new GenericResponse("FAILED", "Sign in again.");
        return new GenericResponse("SUCCESS", "Cities loaded", addresses.cities(u.getOrganizationId()));
    }

    @RequestMapping(value = "/getAreas", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAreas() {
        AuthenticatedUser u = me();
        if (u == null || u.getOrganizationId() == null) return new GenericResponse("FAILED", "Sign in again.");
        return new GenericResponse("SUCCESS", "Areas loaded", addresses.areas(u.getOrganizationId()));
    }

    @RequestMapping(value = "/addCity", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addCity(@RequestParam(value = "name", required = false) String name) {
        AuthenticatedUser u = me();
        if (u == null || u.getOrganizationId() == null) return new GenericResponse("FAILED", "Sign in again.");
        try {
            AddressService.Added r = addresses.addCity(u.getOrganizationId(), u.getUserId(), name);
            return r.created() ? new GenericResponse("SUCCESS", "City added.", r.row())
                    : new GenericResponse("FOUND", r.row().getName() + " already exists.", r.row());
        } catch (IllegalArgumentException refused) {
            return new GenericResponse("FAILED", refused.getMessage());
        } catch (Exception e) {
            LOGGER.error("addCity failed", e);
            return new GenericResponse("ERROR", "Could not add the city.");
        }
    }

    @RequestMapping(value = "/addArea", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addArea(@RequestParam(value = "cityId", required = false) Long cityId,
                                   @RequestParam(value = "name", required = false) String name) {
        AuthenticatedUser u = me();
        if (u == null || u.getOrganizationId() == null) return new GenericResponse("FAILED", "Sign in again.");
        try {
            AddressService.Added r = addresses.addArea(u.getOrganizationId(), u.getUserId(), cityId, name);
            return r.created() ? new GenericResponse("SUCCESS", "Area added.", r.row())
                    : new GenericResponse("FOUND", r.row().getName() + " already exists in " + r.row().getCityName() + ".", r.row());
        } catch (IllegalArgumentException refused) {
            return new GenericResponse("FAILED", refused.getMessage());
        } catch (Exception e) {
            LOGGER.error("addArea failed", e);
            return new GenericResponse("ERROR", "Could not add the area.");
        }
    }
}
