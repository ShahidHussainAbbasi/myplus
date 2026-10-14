package com.myplus.business_service.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.business_service.entity.SalesRep;
import com.myplus.business_service.service.SalesRepService;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

/**
 * SRA-2 — the business's sales reps. Design: {@code microservices/docs/slices/sra-sales-by-rep-and-area.md} §5.
 *
 * <p><b>Reading</b> the list is open to every member: the rep pickers (customer form, and in SRA-3 the order and sale
 * screens) need it. <b>Adding and changing</b> a rep is the {@code rep.manage} permission — DATA, mapped in auth's
 * endpoint_permission (V33) for the browser and checked HERE too, because the list decides who gets credit for a sale
 * and a member with their own token must not reach it around the monolith.
 */
@Controller
public class SalesRepController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());
    static final String MANAGE = "rep.manage";

    @Autowired private SalesRepService reps;
    @Autowired private RequestUtil requestUtil;

    private AuthenticatedUser me() { return requestUtil.getCurrentUser(); }

    static Map<String, Object> view(SalesRep r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("phone", r.getPhone());
        m.put("userId", r.getUserId());
        m.put("status", r.getStatus());
        m.put("version", r.getVersion());
        return m;
    }

    @RequestMapping(value = "/getSalesReps", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getSalesReps() {
        AuthenticatedUser u = me();
        if (u == null || u.getOrganizationId() == null) return new GenericResponse("FAILED", "Sign in again.");
        List<Map<String, Object>> out = new ArrayList<>();
        for (SalesRep r : reps.list(u.getOrganizationId())) out.add(view(r));
        return new GenericResponse("SUCCESS", "Sales reps loaded", null, out);
    }

    @RequestMapping(value = "/addSalesRep", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addSalesRep(@RequestParam(value = "name", required = false) String name,
                                       @RequestParam(value = "phone", required = false) String phone,
                                       @RequestParam(value = "userId", required = false) Long userId) {
        AuthenticatedUser u = me();
        if (u == null || u.getOrganizationId() == null) return new GenericResponse("FAILED", "Sign in again.");
        if (!requestUtil.holds(MANAGE)) return new GenericResponse("FORBIDDEN", "You are not allowed to manage sales reps.");
        try {
            SalesRep r = reps.create(u.getOrganizationId(), u.getUserId(), name, phone, (userId != null && userId == 0L) ? null : userId);
            return new GenericResponse("SUCCESS", "Rep added.", view(r));
        } catch (IllegalArgumentException refused) {
            return new GenericResponse("FAILED", refused.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            return new GenericResponse("FAILED", "That name or login is already on the list.");
        } catch (Exception e) {
            LOGGER.error("addSalesRep failed", e);
            return new GenericResponse("ERROR", "Could not add the rep.");
        }
    }

    @RequestMapping(value = "/updateSalesRep", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse updateSalesRep(@RequestParam("id") Long id,
                                          @RequestParam(value = "name", required = false) String name,
                                          @RequestParam(value = "phone", required = false) String phone,
                                          @RequestParam(value = "userId", required = false) Long userId,
                                          @RequestParam(value = "status", required = false) String status) {
        AuthenticatedUser u = me();
        if (u == null || u.getOrganizationId() == null) return new GenericResponse("FAILED", "Sign in again.");
        if (!requestUtil.holds(MANAGE)) return new GenericResponse("FORBIDDEN", "You are not allowed to manage sales reps.");
        try {
            return new GenericResponse("SUCCESS", "Rep saved.", view(reps.update(u.getOrganizationId(), id, name, phone, userId, status)));
        } catch (IllegalArgumentException refused) {
            return new GenericResponse("FAILED", refused.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            return new GenericResponse("FAILED", "That name or login is already on the list.");
        } catch (org.springframework.dao.OptimisticLockingFailureException conflict) {
            return new GenericResponse("CONFLICT", "Somebody else changed this rep meanwhile. Reload and try again.");
        } catch (Exception e) {
            LOGGER.error("updateSalesRep failed", e);
            return new GenericResponse("ERROR", "Could not save the rep.");
        }
    }
}
