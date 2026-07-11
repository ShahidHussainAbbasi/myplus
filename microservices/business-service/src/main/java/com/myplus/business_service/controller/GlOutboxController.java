package com.myplus.business_service.controller;

import com.myplus.business_service.repository.GlOutboxRepo;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/** Audit #4: read the GL posting outbox (recent rows for the tenant) — for ops visibility + test verification. */
@Controller
public class GlOutboxController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private GlOutboxRepo glOutboxRepo;
    @Autowired
    private RequestUtil requestUtil;

    @RequestMapping(value = "/getGlOutbox", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getGlOutbox() {
        try {
            AuthenticatedUser u = requestUtil.getCurrentUser();
            return new GenericResponse("SUCCESS", "GL outbox",
                    glOutboxRepo.findScoped(u != null ? u.getOrganizationId() : null, u != null ? u.getUserId() : null));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > getGlOutbox " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not load the GL outbox.");
        }
    }
}
