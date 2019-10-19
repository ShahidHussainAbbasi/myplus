/**
 * 
 */
package com.web.controller.agriculture;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.persistence.model.agriculture.AgricultureExpense;
import com.persistence.model.agriculture.Land;
import com.service.agriculture.IAgricultureExpenseService;
import com.service.agriculture.ILandService;
import com.web.dto.agriculture.AgricultureExpenseDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;


/**
 * @author Shahid
 *
 */

//@RequestMapping("/agricultureExpense")
@Controller
public class AgricultureExpenseController {
//	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
//    private static final int BUTTONS_TO_SHOW = 3;
//    private static final int INITIAL_PAGE = 0;
//    private static final int INITIAL_PAGE_SIZE = 5;
//    private static final int[] PAGE_SIZES = { 5, 10};

    @Autowired
	private MessageSource messages;    
	@Autowired
	IAgricultureExpenseService service;
	@Autowired
	ILandService landService;
	@Autowired
	RequestUtil requestUtil;
	
	@Autowired
	AppUtil appUtil;
	
	private ModelMapper modelMapper = new ModelMapper();

	@RequestMapping(value = "/addAgricultureExpense", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addAgricultureExpense(final AgricultureExpenseDTO dto, final HttpServletRequest request){
		try {
			AgricultureExpense obj = new AgricultureExpense(requestUtil.getCurrentUser().getId(),dto.getExpenseName());
			if(appUtil.isEmptyOrNull(dto.getId())) {
				Example<AgricultureExpense> example = Example.of(obj);
				if(service.exists(example))				
					return new GenericResponse(appUtil.INVALID,messages.getMessage("The Expense "+dto.getExpenseName()+" exist or invalid", null, request.getLocale()));
			}

			obj = modelMapper.map(dto, AgricultureExpense.class);
			obj.setUserId(requestUtil.getCurrentUser().getId());
			obj.setDated(LocalDateTime.now());
			obj.setUpdated(appUtil.getDateTime(dto.getUpdatedStr()));
			//update with land name
			Optional<Land> optional = landService.findById(dto.getLandId());
			if(optional.isPresent()) {
				Land land = optional.get();
				obj.setLandId(land.getId());
				obj.setLandName(land.getLandName());
			}
			
			if(service.save(obj).getId()>0)
				return new GenericResponse("Expense added successfully");
			else
				return new GenericResponse("Sorry, Your expense not submitted");
		} catch (Exception e) {
			appUtil.le(this.getClass(), e);
			return new GenericResponse(appUtil.NOT_FOUND,messages.getMessage("Sorry, Your expense not submitted", null, request.getLocale()),dto);
		}
	}

/*	@GetMapping(value = "/loadAgricultureExpense")
    public ModelAndView loadDonators(@RequestParam("pageSize") Optional<Integer> pageSize,@RequestParam("page") Optional<Integer> page,final HttpServletRequest request){
//        ModelAndView modelAndView = new ModelAndView("donators");
        ModelAndView modelAndView = new ModelAndView("abbasiWelfare/donators");
		try {
        // Evaluate page size. If requested parameter is null, return initial
        int evalPageSize = pageSize.orElse(INITIAL_PAGE_SIZE);
        // Evaluate page. If requested parameter is null or less than 0 (to prevent exception), return initial size. Otherwise, return value of param. decreased by 1.
        int evalPage = (page.orElse(0) < 1) ? INITIAL_PAGE : page.get() - 1;
        PageRequest pr = PageRequest.of(evalPage, evalPageSize);
        //Get Donator Id by usertotalPagestotalPages
        Page<Donation> donators  = (Page<Donation>) repository.findAll(pr);
        if(donators == null)
        	return modelAndView;
        
        PagerModel pager = new PagerModel(donators.getTotalPages(),donators.getNumber(),BUTTONS_TO_SHOW);
        modelAndView.addObject("donators",donators);
        // evaluate page size
        modelAndView.addObject("selectedPageSize", evalPageSize);
        // add page sizes
        modelAndView.addObject("pageSizes", PAGE_SIZES);
        // add pager
        modelAndView.addObject("pager", pager);
        
	    }catch(Exception e) {
	    	e.printStackTrace();
	    }
	            
        return modelAndView;
    }	
*/	
	
	
	@RequestMapping(value = "/getUserAgricultureExpense", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserAgricultureExpense(final HttpServletRequest request) {
		AgricultureExpenseDTO dto = null;
		try {
			List<AgricultureExpenseDTO> dtos = new ArrayList<>();
			AgricultureExpense agricultureExpense = new AgricultureExpense(requestUtil.getCurrentUser().getId());
			Example<AgricultureExpense> example = Example.of(agricultureExpense);
			List<AgricultureExpense> objs = service.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse(appUtil.NOT_FOUND,messages.getMessage("message.no.data.found", null, request.getLocale()),objs);
			
			for(AgricultureExpense obj: objs) {
				dto = modelMapper.map(obj, AgricultureExpenseDTO.class);//new AgricultureExpenseDTO();
//				dto.setId(obj.getId());
//				dto.setAmount(obj.getAmount());
				dto.setDatedStr(appUtil.getDateTimeStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateTimeStr(obj.getUpdated()));
				dtos.add(dto);
			}
			return new GenericResponse("SUCCESS",dtos);
		} catch (Exception e) {
			appUtil.le(this.getClass(), e);
			return new GenericResponse(appUtil.ERROR,messages.getMessage("message.system_error"+" : "+e.getCause().toString(), null, request.getLocale()),dto);
		}
	}
	
	@RequestMapping(value = "/expense/loadLastCropAttached", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse loadLastExpenseCropAttached(@RequestParam Long landId,final HttpServletRequest request) {
		AgricultureExpenseDTO dto = null;
		try {
			AgricultureExpense agricultureExpense = new AgricultureExpense(requestUtil.getCurrentUser().getId());
			agricultureExpense.setLandId(landId);
			Example<AgricultureExpense> example = Example.of(agricultureExpense);
			AgricultureExpense obj = service.findAll(example, new Sort(Sort.Direction.DESC, "updated")).get(0);
			if(appUtil.isEmptyOrNull(obj))
				return new GenericResponse(appUtil.NOT_FOUND,messages.getMessage("message.no.data.found", null, request.getLocale()));
			
		
			return new GenericResponse("SUCCESS",obj);
		} catch (Exception e) {
			appUtil.le(this.getClass(), e);
			return new GenericResponse(appUtil.ERROR,messages.getMessage("message.system_error"+" : "+e.getCause().toString(), null, request.getLocale()),dto);
		}
	}

	@RequestMapping(value = "/deleteAgricultureExpense", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse deleteAgricultureExpense( HttpServletRequest request){
		try {
		String ids = request.getParameter("checked");
			if(StringUtils.isEmpty(ids)) 
				return new GenericResponse(appUtil.SUCCESS,messages.getMessage("message.invalid.input", null, request.getLocale()));
				
			String idList[] = ids.split(",");
			for(String id:idList){
				if(!StringUtils.isEmpty(id)) 
					service.deleteById(Long.valueOf(id));
			}
			return new GenericResponse(appUtil.SUCCESS,messages.getMessage("message.delete.success", null, request.getLocale()));
		} catch (Exception e) {
			appUtil.le(this.getClass(), e);
			return new GenericResponse(appUtil.ERROR,messages.getMessage("message.system_error"+" : "+e.getCause().toString(), null, request.getLocale()));
		}
	}

}
