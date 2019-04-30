/**
 * 
 */
package com.web.controller.abbasiWelfare;

import java.time.LocalDateTime;
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
	
	@RequestMapping(value = "/getUserDonations", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserDonations(final HttpServletRequest request) {
		try {
			List<DonatorDTO> dtos = new ArrayList<>();
			List<Donation> objs = donationService.findAll();
			DonatorDTO dto = null;
			for(Donation obj: objs) {
				if(!appUtil.isEmptyOrNull(obj)) {
					dto = new DonatorDTO();
					
//					Donator filterBy = new Donator();
//					filterBy.setName(obj.getName());
//					filterBy.setUserId(obj.getUserId());
//					Example<Donator> example = Example.of(filterBy);
//					List<Donator> dts = donatorService.findAll(example);
//					if(AppUtil.isEmptyOrNull(dts))
//						continue;
//					Donator dt = new Donator();
//					dt = dts.get(0);
					if(!appUtil.isEmptyOrNull(obj.getDonator()) && obj.getDonator().isShowMe()) {
						dto.setName(obj.getDonator().getName());
						dto.setfName(obj.getDonator().getfName());
						dto.setAddress(obj.getDonator().getAddress());
						dto.setMobile(obj.getDonator().getMobile());
						
					}else {
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
			}
			if(!appUtil.isEmptyOrNull(dtos)) {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
			}else {
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserDonation", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserDonation(final HttpServletRequest request) {
		try {
			Donation filterBy = new Donation();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Donation> example = Example.of(filterBy);
			List<Donation> objs = donationService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<DonationDTO> dtos = new ArrayList<>();
			objs.forEach(obj->{
				DonationDTO dto = new DonationDTO();
				dto = modelMapper.map(obj, DonationDTO.class);
				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			if(appUtil.isEmptyOrNull(dtos))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
			else 
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserDonator", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserDonator(final HttpServletRequest request) {
		try {
			Donator filterBy = new Donator();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Donator> example = Example.of(filterBy);
			List<Donator> objs = donatorService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<DonatorDTO> dtos = new ArrayList<>();
			objs.forEach(obj->{
				DonatorDTO dto = new DonatorDTO();
				dto = modelMapper.map(obj, DonatorDTO.class);
				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			if(appUtil.isEmptyOrNull(dtos))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
			else 
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
			
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
			filterBy.setUserId(user.getId());
	        Example<Donator> example = Example.of(filterBy);
			List<Donator> donators = donatorService.findAll(example);
			sb.append("<option data-tokens=''> Nothing Selected </option>");
			donators.forEach(d -> {
				if(d!=null && d.getId()!=null)
					sb.append("<option value='"+d.getId()+"'>"+d.getName()+"</option>");
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	    return sb.toString();
	}


	@RequestMapping(value = "/addDonator", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addDonator(@Validated final DonatorDTO dto, final HttpServletRequest request) {
		try {
			User user = requestUtil.getCurrentUser();
			LocalDateTime dated = LocalDateTime.now();
			Donator obj = new Donator();
			obj.setUserId(user.getId());
			obj.setName(dto.getName());
			Example<Donator> example = Example.of(obj);
			if(appUtil.isEmptyOrNull(dto.getId()) && donatorService.exists(example))
				return new GenericResponse("FOUND",messages.getMessage("The Donator "+dto.getName()+" already exist", null, request.getLocale()));

			else if(!appUtil.isEmptyOrNull(dto.getId())) {
				obj = donatorService.getOne(dto.getId());
				dated = obj.getDated();
			}
			obj  = modelMapper.map(dto, Donator.class);
			obj.setUserId(user.getId());
			if(appUtil.isEmptyOrNull(dto.getId()))
				obj.setDated(dated);
			else
				obj.setDated(dated);
			obj.setUpdated(dated);
			
			obj = donatorService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
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
	public GenericResponse addDonation(@Validated final DonationDTO dto, final HttpServletRequest request) {
		try {
			User user = requestUtil.getCurrentUser();
			Donation obj = new Donation();
			
			LocalDateTime dated = LocalDateTime.now();

			if(!appUtil.isEmptyOrNull(dto.getId())) {
				obj = donationService.getOne(dto.getId());
				if(!appUtil.isEmptyOrNull(obj.getDated()))
					dated = obj.getDated();
			}
			obj  = modelMapper.map(dto, Donation.class);
			if(!appUtil.isEmptyOrNull(dto.getId()))
				obj.setId(dto.getId());

			obj.setUserId(user.getId());
			Donator donator = donatorService.getOne(dto.getDonatorId());
			obj.setDonator(donator);
			if(appUtil.isEmptyOrNull(dto.getId()))
				obj.setDated(dated);
			else
				obj.setDated(dated);
			obj.setUpdated(dated);
			
			obj = donationService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
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
