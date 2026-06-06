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
import com.myplus.education.dto.FeeCollectionDTO;
import com.myplus.education.entity.FeeCollection;
import com.myplus.education.repository.FeeCollectionRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Flat (legacy) Fee Collection endpoints — core list/add/delete. userId-scoped.
 * NOTE: loadFV (fee voucher), loadFL/loadFR (ledger/receipt) and findFc are deferred to a focused
 * follow-up (voucher computation + student/grade joins).
 */
@Controller
public class FeeCollectionController {

    @Autowired
    private FeeCollectionRepository feeCollectionRepository;
    @Autowired
    private RequestUtil requestUtil;
    @Autowired
    private AppUtil appUtil;

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    private FeeCollectionDTO toDto(FeeCollection o) {
        FeeCollectionDTO dto = new FeeCollectionDTO();
        dto.setId(o.getId());
        dto.setUserId(o.getUserId());
        dto.setEn(o.getEn());
        dto.setDt(o.getDt());
        dto.setD(o.getD());
        dto.setDd(o.getDd());
        dto.setDa(o.getDa());
        dto.setF(o.getF());
        dto.setFp(o.getFp());
        dto.setOd(o.getOd());
        dto.setOdd(o.getOdd());
        dto.setP(o.getP());
        dto.setRb(o.getRb());
        dto.setRi(o.getRi());
        dto.setPdStr(appUtil.getLocalDateStr(o.getPd()));
        return dto;
    }

    @RequestMapping(value = "/getUserFc", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserFc(final HttpServletRequest request) {
        try {
            List<FeeCollection> objs = feeCollectionRepository.findByUserId(userId());
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getAllFc", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllFc(final HttpServletRequest request) {
        try {
            List<FeeCollection> all = feeCollectionRepository.findAll();
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addFc", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addFc(final FeeCollectionDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            FeeCollection obj = (dto.getId() != null)
                    ? feeCollectionRepository.findById(dto.getId()).orElseGet(FeeCollection::new)
                    : new FeeCollection();
            obj.setUserId(userId);
            obj.setEn(dto.getEn());
            obj.setDt(dto.getDt());
            obj.setD(dto.getD());
            obj.setDd(dto.getDd());
            obj.setDa(dto.getDa());
            obj.setF(dto.getF());
            obj.setFp(dto.getFp());
            obj.setOd(dto.getOd());
            obj.setOdd(dto.getOdd());
            obj.setP(dto.getP());
            obj.setRb(dto.getRb());
            obj.setRi(dto.getRi());
            if (!appUtil.isEmptyOrNull(dto.getPdStr())) {
                obj.setPd(appUtil.getLocalDate(dto.getPdStr()));
            }
            FeeCollection saved = feeCollectionRepository.save(obj);
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "")
                    : new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteFc", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteFc(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                for (String id : ids.split(",")) {
                    if (!StringUtils.isEmpty(id)) {
                        feeCollectionRepository.deleteById(Long.valueOf(id));
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
