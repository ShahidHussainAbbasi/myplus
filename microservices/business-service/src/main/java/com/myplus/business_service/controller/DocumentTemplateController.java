package com.myplus.business_service.controller;

import com.myplus.business_service.entity.DocumentTemplate;
import com.myplus.business_service.service.DocumentProfileValidator;
import com.myplus.business_service.service.DocumentTemplateService;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/**
 * B2B Phase 3g — CRUD for owner-designed document layouts, plus the field whitelist the designer renders from.
 *
 * <p><b>Owner-gated on every write (decision D-5).</b> A layout decides what appears on every invoice the
 * business issues — its name, its licence number, which figures a customer sees. That is an owner's call,
 * consistent with how the Finance screens are gated, not something a cashier changes mid-shift. Reads are
 * open to any authenticated user of the tenant because the printer itself needs them.
 *
 * <p>Every read and write is org-scoped through the service's {@code findByIdScoped}, so an id from the
 * client can never reach another tenant's layout.
 */
@Controller
public class DocumentTemplateController {

    private static final String OWNER_ONLY =
            "hasAuthority('ROLE_OWNER') or hasAuthority('SUPER_PRIVILEGE') or hasAuthority('ADMIN_PRIVILEGE')";

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired DocumentTemplateService templateService;
    @Autowired DocumentProfileValidator validator;
    @Autowired RequestUtil requestUtil;

    private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u == null ? null : u.getUserId(); }
    private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u == null ? null : u.getOrganizationId(); }

    @RequestMapping(value = "/documentTemplates", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse list() {
        try {
            return new GenericResponse("SUCCESS", null, templateService.list(orgId(), userId()));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > documentTemplates " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not load the document layouts.");
        }
    }

    @RequestMapping(value = "/documentTemplate", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse byId(@RequestParam("id") Long id) {
        try {
            return templateService.byId(id, orgId(), userId())
                    .map(t -> new GenericResponse("SUCCESS", null, t))
                    .orElseGet(() -> new GenericResponse("NOT_FOUND", "Layout not found."));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > documentTemplate " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not load the layout.");
        }
    }

    /**
     * The renderer's field whitelist, so the designer offers exactly the fields this build can bind — and no
     * others. Served rather than hardcoded in the browser so a field added to the renderer cannot be
     * forgotten in the designer, and so the designer can never offer something the validator will reject.
     */
    @RequestMapping(value = "/documentFields", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse fields() {
        return new GenericResponse("SUCCESS", null, validator.whitelist());
    }

    @PreAuthorize(OWNER_ONLY)
    @RequestMapping(value = "/saveDocumentTemplate", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse save(@RequestBody DocumentTemplate dto) {
        try {
            if (dto.getName() == null || dto.getName().isBlank())
                return new GenericResponse("FAILED", "Give the layout a name.");
            return new GenericResponse("SUCCESS", "Layout saved.",
                    templateService.save(dto, orgId(), userId()));
        } catch (DocumentProfileValidator.InvalidProfileException e) {
            // The owner's own mistake, in their own words — not a stack trace and not "unexpected error".
            return new GenericResponse("FAILED", e.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return new GenericResponse("FOUND", "A layout with that name already exists.");
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > saveDocumentTemplate " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not save the layout.");
        }
    }

    @PreAuthorize(OWNER_ONLY)
    @RequestMapping(value = "/deleteDocumentTemplate", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse delete(@RequestParam("id") Long id) {
        try {
            return templateService.delete(id, orgId(), userId())
                    ? new GenericResponse("SUCCESS", "Layout deleted.")
                    : new GenericResponse("NOT_FOUND", "Layout not found.");
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > deleteDocumentTemplate " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not delete the layout.");
        }
    }
}
