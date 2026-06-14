package com.myplus.education.controller;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.dto.DiscountDTO;
import com.myplus.education.entity.Discount;
import com.myplus.education.repository.DiscountRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/** Flat (legacy) Discount endpoints. userId-scoped. */
@Controller
public class DiscountController {

    @Autowired
    private DiscountRepository discountRepository;
    @Autowired
    private RequestUtil requestUtil;
    @Autowired
    private AppUtil appUtil;

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private DiscountDTO toDto(Discount o) {
        DiscountDTO dto = new DiscountDTO();
        dto.setId(o.getId());
        dto.setUserId(o.getUserId());
        dto.setName(o.getName());
        dto.setDi(o.getDi());
        dto.setAmount(o.getAmount());
        dto.setStartDateStr(appUtil.getLocalDateStr(o.getStartDate()));
        dto.setEndDateStr(appUtil.getLocalDateStr(o.getEndDate()));
        dto.setDescription(o.getDescription());
        dto.setReferenceName(o.getReferenceName());
        dto.setReferenceMobile(o.getReferenceMobile());
        dto.setStatus(o.getStatus());
        return dto;
    }

    @RequestMapping(value = "/getUserDiscount", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserDiscount(final HttpServletRequest request) {
        try {
            List<Discount> objs = discountRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getUserDiscounts", method = RequestMethod.GET)
    @ResponseBody
    public String getUserDiscounts(final HttpServletRequest request) {
        StringBuffer sb = new StringBuffer();
        try {
            List<Discount> objs = discountRepository.findScoped(orgId(), userId());
            sb.append("<option value=''>Nothing Selected</option>");
            objs.forEach(d -> {
                if (d != null && d.getId() != null) {
                    sb.append("<option value=" + d.getId() + ">" + d.getName() + "</option>");
                }
            });
        } catch (Exception e) {
            appUtil.le(getClass(), e);
        }
        return sb.toString();
    }

    @RequestMapping(value = "/getAllDiscount", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllDiscount(final HttpServletRequest request) {
        try {
            // Tenant-scoped: "all" means all discounts in the active organization, not every tenant's.
            List<Discount> all = discountRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addDiscount", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addDiscount(final DiscountDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            if (appUtil.isEmptyOrNull(dto.getId())) {
                boolean exists = discountRepository.findScoped(orgId, userId).stream()
                        .anyMatch(o -> o.getName() != null && o.getName().equalsIgnoreCase(dto.getName()));
                if (exists) {
                    return new GenericResponse("FOUND", "The Discount '" + dto.getName() + "' already exists");
                }
            }
            Discount obj = (dto.getId() != null)
                    ? discountRepository.findById(dto.getId()).orElseGet(Discount::new)
                    : new Discount();
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setName(dto.getName());
            obj.setDi(dto.getDi());
            obj.setAmount(dto.getAmount());
            obj.setDescription(dto.getDescription());
            obj.setReferenceName(dto.getReferenceName());
            obj.setReferenceMobile(dto.getReferenceMobile());
            obj.setStatus(dto.getStatus());
            if (!appUtil.isEmptyOrNull(dto.getStartDateStr())) {
                obj.setStartDate(appUtil.getLocalDate(dto.getStartDateStr()));
            }
            if (!appUtil.isEmptyOrNull(dto.getEndDateStr())) {
                obj.setEndDate(appUtil.getLocalDate(dto.getEndDateStr()));
            }
            Discount saved = discountRepository.save(obj);
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "")
                    : new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteDiscount", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteDiscount(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                for (String id : ids.split(",")) {
                    if (!StringUtils.isEmpty(id)) {
                        discountRepository.deleteById(Long.valueOf(id));
                    }
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return false;
        }
    }
}
