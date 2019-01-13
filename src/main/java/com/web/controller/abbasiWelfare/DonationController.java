/**
 * 
 */
package com.web.controller.abbasiWelfare;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.persistence.model.User;
import com.persistence.model.abbasiWelfare.Donation;
import com.persistence.model.abbasiWelfare.Donator;
import com.service.abbasiWelfare.DonationService;
import com.service.abbasiWelfare.DonatorService;
import com.web.dto.abbasiWelfare.DonationDTO;
import com.web.dto.abbasiWelfare.DonatorDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;


/**
 * @author Shahid
 *
 */

//@RequestMapping("/donator")
@Controller
public class DonationController {
	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
//    private static final int BUTTONS_TO_SHOW = 3;
//    private static final int INITIAL_PAGE = 0;
//    private static final int INITIAL_PAGE_SIZE = 5;
//    private static final int[] PAGE_SIZES = { 5, 10};

    @Autowired
	private MessageSource messages;    
	@Autowired
	DonatorService donatorService;
	@Autowired
	DonationService donationService;
	@Autowired
	RequestUtil requestUtil;
	
	@Autowired
	AppUtil appUtil;
	
	private ModelMapper modelMapper = new ModelMapper();
//
//	@RequestMapping(value = "/addDonation", method = RequestMethod.POST)
//	@ResponseBody
//	public GenericResponse addDonation(final DonatorDTO donatorDTO, final HttpServletRequest request){
//		try {
//			donatorDTO.setDated(AppUtil.todayDateStr());
//			Donation donator = modelMapper.map(donatorDTO, Donation.class);
//			if(donatorService.save(donator).getId()>0)
//				return new GenericResponse("Thank you, You have submitted  your donation successfully");
//			else
//				return new GenericResponse("Sorry, Your donatioin not submitted");
//		} catch (Exception e) {
//			e.printStackTrace();
//			LOGGER.debug(this.getClass().getName(), e.getCause()+"");
//			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),e.getCause().toString());
//		}
//	}
//
//	@GetMapping(value = "/loadDonators")
//    public ModelAndView loadDonators(@RequestParam("pageSize") Optional<Integer> pageSize,@RequestParam("page") Optional<Integer> page,final HttpServletRequest request){
////        ModelAndView modelAndView = new ModelAndView("donators");
//        ModelAndView modelAndView = new ModelAndView("abbasiWelfare/donators");
//		try {
//        // Evaluate page size. If requested parameter is null, return initial
//        int evalPageSize = pageSize.orElse(INITIAL_PAGE_SIZE);
//        // Evaluate page. If requested parameter is null or less than 0 (to prevent exception), return initial size. Otherwise, return value of param. decreased by 1.
//        int evalPage = (page.orElse(0) < 1) ? INITIAL_PAGE : page.get() - 1;
//        PageRequest pr = PageRequest.of(evalPage, evalPageSize);
//        //Get Donator Id by usertotalPagestotalPages
//        Page<Donation> donators  = (Page<Donation>) repository.findAll(pr);
//        if(donators == null)
//        	return modelAndView;
//        
//        PagerModel pager = new PagerModel(donators.getTotalPages(),donators.getNumber(),BUTTONS_TO_SHOW);
//        modelAndView.addObject("donators",donators);
//        // evaluate page size
//        modelAndView.addObject("selectedPageSize", evalPageSize);
//        // add page sizes
//        modelAndView.addObject("pageSizes", PAGE_SIZES);
//        // add pager
//        modelAndView.addObject("pager", pager);
//        
//	    }catch(Exception e) {
//	    	e.printStackTrace();
//	    }
//	            
//        return modelAndView;
//    }	
//	
//	
	
	@RequestMapping(value = "/getAllDonations", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllDonations(final HttpServletRequest request) {
		try {
			List<DonatorDTO> dtos = new ArrayList<>();
			List<Donation> donations = donationService.findAll();
			DonatorDTO dto = null;
			for(Donation d: donations) {
				if(!appUtil.isEmptyOrNull(d)) {
					dto = new DonatorDTO();
					
					Donator filterBy = new Donator();
					filterBy.setName(d.getName());
					Example<Donator> example = Example.of(filterBy);
					List<Donator> dts = donatorService.findAll(example);
					if(appUtil.isEmptyOrNull(dts))
						continue;
					Donator dt = new Donator();
					dt = dts.get(0);
					if(dt.isShowMe()) {
						dto.setName(dt.getName());
						dto.setfName(dt.getfName());
						dto.setAddress(dt.getAddress());
						dto.setAmount(d.getAmount());
						dto.setMobile(dt.getMobile());
						
						dto.setDated(d.getDated());
						dto.setReceivedBy(d.getReceivedBy());
					}else {
						dto.setName("");
						dto.setfName("");
						dto.setAddress("");
						dto.setAmount(0.0F);
						dto.setMobile("");
						
						dto.setDated(d.getDated());
						dto.setReceivedBy(d.getReceivedBy());
					}
					dtos.add(dto);
				}
			}
			if(!appUtil.isEmptyOrNull(dtos)) {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
			}else {
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),donations);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getAllDonation", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllDonation(final HttpServletRequest request) {
		try {
			List<Donation> donations = donationService.findAll();
			if(!appUtil.isEmptyOrNull(donations)) {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),donations);
			}else {
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),donations);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getAllDonator", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse loadAllDonator(final HttpServletRequest request) {
		try {
			List<Donator> donators = donatorService.findAll();
			if(donators.size()>0) {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),donators);
			}else {
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),donators);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/getAllDonators", method = RequestMethod.GET)
	@ResponseBody
	public String getAllDonators() {
		StringBuffer sb = new StringBuffer();
		try {
			Donator filterBy = new Donator();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserType(user.getUserType());
	        Example<Donator> example = Example.of(filterBy);
			List<Donator> donators = donatorService.findAll(example);
			sb.append("<option data-tokens=''> Nothing Selected </option>");
			donators.forEach(d -> {
				if(d!=null && d.getId()!=null)
					sb.append("<option value='"+d.getName()+"'>"+d.getName()+" - "+d.getfName()+"</option>");
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	    return sb.toString();
	}


	@RequestMapping(value = "/addDonator", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addDonator(@Validated final DonatorDTO donatorDTO, final HttpServletRequest request) {
		try {
			User user = requestUtil.getCurrentUser();
			Donator donator = new Donator(user.getId(),user.getUserType(),donatorDTO.getName());
			Example<Donator> example = Example.of(donator);
			if(donatorService.exists(example)) {
				return new GenericResponse("FOUND",messages.getMessage(donatorDTO.getName()+" already exist", null, request.getLocale()));
			}
			donatorDTO.setUserId(user.getId());
			donatorDTO.setUserType(user.getUserType());
			donator = modelMapper.map(donatorDTO, Donator.class);
			donator.setDated(AppUtil.todayDateStr());
			Donator donatorTemp = donatorService.save(donator);
			if(donatorTemp.getId()>0) {
				return new GenericResponse("SUCCESS",donatorTemp);
			}else {
				LOGGER.warn("Your donator can't be added, Please contact with your Admin");
				return new GenericResponse("FAILED",messages.getMessage("Your donator can't be added, Please contact with your Admin", null, request.getLocale()));
			}
		} catch (Exception e) {
			LOGGER.error(e.getClass().getName()+" : "+e.getMessage());
			return new GenericResponse("ERROR",messages.getMessage("There is system erro, Please try again lator or contact with System Admin", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addDonation", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addDonation(@Validated final DonationDTO donationDTO, final HttpServletRequest request) {
		try {
			Donation donation = new Donation();
			User user = requestUtil.getCurrentUser();
			donationDTO.setUserId(user.getId());
			donationDTO.setUserType(user.getUserType());
			donation = modelMapper.map(donationDTO, Donation.class);
			donation.setDated(AppUtil.todayDateStr());
			if(donation.getId()!=null && donation.getId()>0) {
				Example<Donation> example = Example.of(donation);
				if(donationService.exists(example)) {
					return new GenericResponse("FOUND",messages.getMessage("Same donation same day does not allowed", null, request.getLocale()));
				}
			}
			Donation companyTemp = donationService.save(donation);
			if(companyTemp.getId()>0) {
				return new GenericResponse("SUCCESS",companyTemp);
			}else {
				return new GenericResponse("FAILED",messages.getMessage("Your donator can't be added, Please contact with your Admin", null, request.getLocale()));
			}
		} catch (Exception e) {
			LOGGER.error(e.getClass().getName()+" : "+e.getMessage());
			return new GenericResponse("ERROR",messages.getMessage("There is system erro, Please try again lator or contact with System Admin", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/deleteDonator", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteDonator( HttpServletRequest req){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					donatorService.deleteById(Long.valueOf(id));
				}
				
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
//			companyRepo.deleteById(id);delete(company);
		} catch (Exception e) {
			LOGGER.error(e.getClass().getName()+" : "+e.getMessage());
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}

	@RequestMapping(value = "/deleteDonation", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteDonation( HttpServletRequest req){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					donationService.deleteById(Long.valueOf(id));
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
//			companyRepo.deleteById(id);delete(company);
		} catch (Exception e) {
			LOGGER.error(e.getClass().getName()+" : "+e.getMessage());
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}

}
