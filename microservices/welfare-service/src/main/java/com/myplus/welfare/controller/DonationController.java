package com.myplus.welfare.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.welfare.dto.DonationDTO;
import com.myplus.welfare.dto.DonatorDTO;
import com.myplus.welfare.entity.Donation;
import com.myplus.welfare.entity.Donator;
import com.myplus.welfare.service.IDonationService;
import com.myplus.welfare.service.IDonatorService;
import com.myplus.welfare.util.AppUtil;
import com.myplus.welfare.util.GenericResponse;
import com.myplus.welfare.util.RequestUtil;

@Controller
public class DonationController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    IDonatorService donatorService;
    @Autowired
    IDonationService donationService;
    @Autowired
    RequestUtil requestUtil;
    @Autowired
    AppUtil appUtil;

    private final ModelMapper modelMapper = new ModelMapper();

    @RequestMapping(value = "/getUserDonations", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserDonations(final HttpServletRequest request) {
        try {
            List<DonatorDTO> dtos = new ArrayList<>();
            List<Donation> objs = donationService.findAll();
            for (Donation obj : objs) {
                if (appUtil.isEmptyOrNull(obj)) {
                    continue;
                }
                DonatorDTO dto = new DonatorDTO();
                if (!appUtil.isEmptyOrNull(obj.getDonator()) && Boolean.TRUE.equals(obj.getDonator().isShowMe())) {
                    dto.setName(obj.getDonator().getName());
                    dto.setfName(obj.getDonator().getfName());
                    dto.setAddress(obj.getDonator().getAddress());
                    dto.setMobile(obj.getDonator().getMobile());
                } else {
                    dto.setName("");
                    dto.setfName("");
                    dto.setAddress("");
                    dto.setMobile("");
                }
                dto.setId(obj.getId());
                dto.setAmount(obj.getAmount());
                dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
                dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
                dto.setReceivedBy(obj.getReceivedBy());
                dtos.add(dto);
            }
            String status = appUtil.isEmptyOrNull(dtos) ? "NOT_FOUND" : "SUCCESS";
            return new GenericResponse(status, "", dtos);
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > getUserDonations", e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getUserDonation", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserDonation(final HttpServletRequest request) {
        try {
            List<DonationDTO> dtos = new ArrayList<>();
            Donation filterBy = new Donation();
            AuthenticatedUser user = requestUtil.getCurrentUser();
            filterBy.setUserId(user.getUserId());
            Example<Donation> example = Example.of(filterBy);
            List<Donation> objs = donationService.findAll(example);
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "", dtos);
            }
            objs.forEach(obj -> {
                DonationDTO dto = modelMapper.map(obj, DonationDTO.class);
                dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
                dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
                dtos.add(dto);
            });
            return new GenericResponse(appUtil.isEmptyOrNull(dtos) ? "NOT_FOUND" : "SUCCESS", "", dtos);
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > getUserDonation", e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getUserDonator", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserDonator(final HttpServletRequest request) {
        try {
            Donator filterBy = new Donator();
            AuthenticatedUser user = requestUtil.getCurrentUser();
            filterBy.setUserId(user.getUserId());
            Example<Donator> example = Example.of(filterBy);
            List<Donator> objs = donatorService.findAll(example);
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            List<DonatorDTO> dtos = new ArrayList<>();
            objs.forEach(obj -> {
                DonatorDTO dto = modelMapper.map(obj, DonatorDTO.class);
                dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
                dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
                dtos.add(dto);
            });
            return new GenericResponse(appUtil.isEmptyOrNull(dtos) ? "NOT_FOUND" : "SUCCESS", "", dtos);
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > getUserDonator", e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getAllDonators", method = RequestMethod.GET)
    @ResponseBody
    public String getAllDonators() {
        StringBuffer sb = new StringBuffer();
        try {
            Donator filterBy = new Donator();
            AuthenticatedUser user = requestUtil.getCurrentUser();
            filterBy.setUserId(user.getUserId());
            Example<Donator> example = Example.of(filterBy);
            List<Donator> donators = donatorService.findAll(example);
            sb.append("<option data-tokens=''> Nothing Selected </option>");
            donators.forEach(d -> {
                if (d != null && d.getId() != null) {
                    sb.append("<option value='" + d.getId() + "'>" + d.getName() + "</option>");
                }
            });
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > getAllDonators", e);
        }
        return sb.toString();
    }

    @RequestMapping(value = "/addDonator", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addDonator(@Validated final DonatorDTO dto, final HttpServletRequest request) {
        try {
            AuthenticatedUser user = requestUtil.getCurrentUser();
            LocalDateTime dated = LocalDateTime.now();
            Donator obj = new Donator();
            obj.setUserId(user.getUserId());
            obj.setName(dto.getName());
            Example<Donator> example = Example.of(obj);
            if (appUtil.isEmptyOrNull(dto.getId()) && donatorService.exists(example)) {
                return new GenericResponse("FOUND", "The Donator " + dto.getName() + " already exists");
            } else if (!appUtil.isEmptyOrNull(dto.getId())) {
                Donator existing = donatorService.getOne(dto.getId());
                if (existing != null) {
                    dated = existing.getDated();
                }
            }
            obj = modelMapper.map(dto, Donator.class);
            obj.setUserId(user.getUserId());
            obj.setDated(dated);
            obj.setUpdated(LocalDateTime.now());

            obj = donatorService.save(obj);
            if (appUtil.isEmptyOrNull(obj)) {
                return new GenericResponse("FAILED", "Your donator can't be added, please contact your Admin");
            }
            return new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > addDonator", e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addDonation", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addDonation(@Validated final DonationDTO dto, final HttpServletRequest request) {
        try {
            AuthenticatedUser user = requestUtil.getCurrentUser();
            Donation obj = new Donation();
            LocalDateTime dated = LocalDateTime.now();

            if (!appUtil.isEmptyOrNull(dto.getId())) {
                Donation existing = donationService.getOne(dto.getId());
                if (existing != null && !appUtil.isEmptyOrNull(existing.getDated())) {
                    dated = existing.getDated();
                }
            }
            obj = modelMapper.map(dto, Donation.class);
            if (!appUtil.isEmptyOrNull(dto.getId())) {
                obj.setId(dto.getId());
            }
            obj.setUserId(user.getUserId());
            Donator donator = donatorService.getOne(dto.getDonatorId());
            obj.setDonator(donator);
            obj.setDated(dated);
            obj.setUpdated(LocalDateTime.now());

            obj = donationService.save(obj);
            if (appUtil.isEmptyOrNull(obj)) {
                return new GenericResponse("FAILED", "");
            }
            return new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > addDonation", e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteDonator", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteDonator(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                for (String id : ids.split(",")) {
                    donatorService.deleteById(Long.valueOf(id));
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > deleteDonator", e);
            return false;
        }
    }

    @RequestMapping(value = "/deleteDonation", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteDonation(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                for (String id : ids.split(",")) {
                    donationService.deleteById(Long.valueOf(id));
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > deleteDonation", e);
            return false;
        }
    }
}
