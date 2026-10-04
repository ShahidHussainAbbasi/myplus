package com.myplus.business_service.controller;

import java.util.ArrayList;
import java.util.List;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.business_service.dto.StoreDTO;
import com.myplus.business_service.entity.Store;
import com.myplus.business_service.service.StoreService;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

/**
 * Store registry CRUD (multi-location Pattern A). Listing is available to any member (to pick a store);
 * create/update require a whole-org role (owner/admin) — the same rule as the other management actions.
 */
@Controller
public class StoreController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private StoreService storeService;

    @Autowired
    private RequestUtil requestUtil;

    @org.springframework.beans.factory.annotation.Autowired
    private ModelMapper modelMapper;   // MM-1: the plain profile (config/MapperProfiles)

    private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u == null ? null : u.getUserId(); }
    private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u == null ? null : u.getOrganizationId(); }
    private boolean inMyTenant(Store s) {
        return (s.getOrganizationId() != null && s.getOrganizationId().equals(orgId()))
            || (s.getOrganizationId() == null && s.getUserId() != null && s.getUserId().equals(userId()));
    }

    /** List the org's stores (for the location switcher + management). */
    @RequestMapping(value = "/getStores", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getStores() {
        try {
            List<StoreDTO> dtos = new ArrayList<>();
            storeService.findScoped(orgId(), userId()).forEach(s -> dtos.add(modelMapper.map(s, StoreDTO.class)));
            return new GenericResponse("SUCCESS", "Stores loaded", dtos);
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > getStores " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not load stores.");
        }
    }

    /** P5b — the stores THIS caller may work at, for the store switcher: their granted stores, or every store in
     *  the org for an owner (grants never narrow an owner) / a caller with no grants (single-store, unchanged).
     *  Distinct from {@link #getStores}, which is the owner's management list of every store in the org. */
    @RequestMapping(value = "/getMyStores", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getMyStores() {
        try {
            java.util.Set<Long> mine = requestUtil.accessibleStoreIds();
            Long active = requestUtil.activeStoreId();
            List<StoreDTO> dtos = new ArrayList<>();
            storeService.findScoped(orgId(), userId()).stream()
                    .filter(s -> requestUtil.isOwnerSuper() || mine.isEmpty() || mine.contains(s.getId()))
                    // L15: a deactivated store is not somewhere anyone can work — it was offered in the switcher
                    // and in the team store picker regardless. getStores (the management list) still shows it,
                    // which is where it is reactivated.
                    .filter(StoreController::isOffered)
                    .forEach(s -> {
                        StoreDTO dto = modelMapper.map(s, StoreDTO.class);
                        dto.setActive(active != null && active.equals(s.getId()));
                        dtos.add(dto);
                    });
            return new GenericResponse("SUCCESS", "Stores loaded", dtos);
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > getMyStores " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not load stores.");
        }
    }

    /** Create a store (owner/admin). */
    @RequestMapping(value = "/addStore", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addStore(@RequestBody StoreDTO dto) {
        try {
            if (!requestUtil.callerSeesWholeOrg())
                return new GenericResponse("FORBIDDEN", "Only an owner or admin can create stores.");
            if (dto.getName() == null || dto.getName().isBlank())
                return new GenericResponse("FAILED", "Store name is required.");
            Store s = new Store();
            s.setName(dto.getName().trim());
            s.setCode(dto.getCode());
            s.setAddress(dto.getAddress());
            s.setPhone(dto.getPhone());
            s.setStatus(dto.getStatus() == null ? "ACTIVE" : dto.getStatus());
            s.setUserId(userId());              // creator (audit)
            s.setOrganizationId(orgId());       // tenant scope
            s = storeService.save(s);
            return new GenericResponse("SUCCESS", "Store created", modelMapper.map(s, StoreDTO.class));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > addStore " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not create the store.");
        }
    }

    /** Update a store (owner/admin, within the caller's tenant). */
    @RequestMapping(value = "/updateStore", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse updateStore(@RequestBody StoreDTO dto) {
        try {
            if (!requestUtil.callerSeesWholeOrg())
                return new GenericResponse("FORBIDDEN", "Only an owner or admin can update stores.");
            if (dto.getId() == null)
                return new GenericResponse("FAILED", "Store id is required.");
            Store s = storeService.findById(dto.getId()).orElse(null);
            if (s == null || !inMyTenant(s))            // anti-IDOR: never touch another tenant's store
                return new GenericResponse("NOT_FOUND", "Store not found.");
            if (dto.getName() != null && !dto.getName().isBlank()) s.setName(dto.getName().trim());
            s.setCode(dto.getCode());
            s.setAddress(dto.getAddress());
            s.setPhone(dto.getPhone());
            if (dto.getStatus() != null) {
                String refusal = refuseStatusChange(s, dto.getStatus(), requestUtil.activeStoreId());
                if (refusal != null) return new GenericResponse("FAILED", refusal);
                s.setStatus(dto.getStatus().trim().toUpperCase());
            }
            s = storeService.save(s);
            return new GenericResponse("SUCCESS", "Store updated", modelMapper.map(s, StoreDTO.class));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > updateStore " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not update the store.");
        }
    }

    static final String ACTIVE = "ACTIVE";
    static final String INACTIVE = "INACTIVE";

    /** L15 — may this store be offered as somewhere to work (switcher, team picker)? Legacy null = ACTIVE. */
    static boolean isOffered(Store s) {
        return s != null && !INACTIVE.equalsIgnoreCase(s.getStatus());
    }

    /**
     * L15 — why this status change is refused, or {@code null} to allow it.
     *
     * <p>Only the two states the screen offers are accepted: the switcher filters on INACTIVE, so a typo
     * ("Inactiv") would read as active everywhere while the owner believed the store closed.
     *
     * <p>The caller's OWN active store cannot be deactivated: every sale and purchase they record is stamped
     * with the active store from their token, so they would go on writing into a store they had just closed.
     * They switch first. (Another user whose token still names it keeps it until they switch — the switcher
     * no longer offers it, and opens on "Select a store…" for them.)
     */
    static String refuseStatusChange(Store s, String requested, Long callerActiveStoreId) {
        String to = requested == null ? "" : requested.trim().toUpperCase();
        if (!ACTIVE.equals(to) && !INACTIVE.equals(to))
            return "Status must be ACTIVE or INACTIVE.";
        if (INACTIVE.equals(to) && callerActiveStoreId != null && callerActiveStoreId.equals(s.getId()))
            return "You are working in this store. Switch to another store before deactivating it.";
        return null;
    }
}
