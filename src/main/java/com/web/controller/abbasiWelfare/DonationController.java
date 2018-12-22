/**
 * 
 */
package com.web.controller.abbasiWelfare;

import java.util.Optional;

import javax.servlet.http.HttpServletRequest;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import com.persistence.Repo.abbasiWelfare.DonatorRepository;
import com.persistence.model.abbasiWelfare.Donator;
import com.service.abbasiWelfare.DonatorService;
import com.web.dto.abbasiWelfare.DonatorDTO;
import com.web.pagination.model.PagerModel;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;

/**
 * @author Shahid
 *
 */

//@RequestMapping("/donator")
@Controller
public class DonationController {
	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
    private static final int BUTTONS_TO_SHOW = 3;
    private static final int INITIAL_PAGE = 0;
    private static final int INITIAL_PAGE_SIZE = 5;
    private static final int[] PAGE_SIZES = { 5, 10};
    @Autowired
    DonatorRepository repository;
    @Autowired
	private MessageSource messages;    
	@Autowired
	DonatorService donatorService;
	
	private ModelMapper modelMapper = new ModelMapper();

	@RequestMapping(value = "/addDonation", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addDonation(final DonatorDTO donatorDTO, final HttpServletRequest request){
		try {
			donatorDTO.setDated(AppUtil.todayDateStr());
			Donator donator = modelMapper.map(donatorDTO, Donator.class);
			if(donatorService.addDonator(donator).getId()>0)
				return new GenericResponse("Thank you, You have submitted  your donation successfully");
			else
				return new GenericResponse("Sorry, Your donatioin not submitted");
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.debug(this.getClass().getName(), e.getCause()+"");
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),e.getCause().toString());
		}
	}

	@GetMapping(value = "/loadDonators")
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
        Page<Donator> donators  = (Page<Donator>) repository.findAll(pr);
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
//	public String loadDonators(Model model, @RequestParam("page") Optional<Integer> page, @RequestParam("size") Optional<Integer> size) {
//		List<DonatorDTO> donators = new ArrayList<DonatorDTO>();
//		model.addAttribute("donators", donators);
//
//		int currentPage = page.orElse(1);
//		int pageSize = size.orElse(5);
//		Page<DonatorDTO> bookPage = donatorService.findPaginated(PageRequest.of(currentPage - 1, pageSize));
//		model.addAttribute("bookPage", bookPage);
//		int totalPages = bookPage.getTotalPages();
//		if (totalPages > 0) {
//			List<Integer> pageNumbers = IntStream.rangeClosed(1, totalPages).boxed().collect(Collectors.toList());
//			model.addAttribute("pageNumbers", pageNumbers);
//		}
//		return "abbasiWelfare/donators.html";
////		return donators;
//	}
}
