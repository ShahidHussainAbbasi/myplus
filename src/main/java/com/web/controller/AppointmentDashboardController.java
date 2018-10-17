/**
 * 
 */
package com.web.controller;

import java.security.Principal;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;

import com.persistence.dao.AppointmentDashboardRepository;
import com.persistence.model.Appointment;
import com.web.dto.AppointmentDashboardDTO;
import com.web.pagination.model.PagerModel;
import com.web.util.GenericResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.ThrowsAdvice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * @author sabbasi
 *
 */
@Controller
public class AppointmentDashboardController {
	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
    private static final int BUTTONS_TO_SHOW = 3;
    private static final int INITIAL_PAGE = 0;
    private static final int INITIAL_PAGE_SIZE = 5;
    private static final int[] PAGE_SIZES = { 5, 10};
    @Autowired
    AppointmentDashboardRepository repository;
    @Autowired
	private MessageSource messages;    
    
    @GetMapping({"/","/appointmentDashboard"})
    public ModelAndView appointmentDashboard(@RequestParam("pageSize") Optional<Integer> pageSize,@RequestParam("page") Optional<Integer> page){
        ModelAndView modelAndView = new ModelAndView("appointmentDashboard");
		try {
//			LOGGER.debug("Registering patient's information");
//			Principal principal = request.getUserPrincipal();
//			if (principal == null || principal.getName() == null) {
////	            final String message = messages.getMessage("label.badUser.title", null, locale);
////	            modelAndView.addAttribute("message", message);
////	            return "redirect:/badUser.html?lang=" + locale.getLanguage();
//				modelAndView = new ModelAndView("redirect:/badUser.html");
//	            return modelAndView;
//			}
//    	//Check if user is logged in
//    	
        if(repository.count()!=0){
            ;//pass
        }else{
        	System.out.println("Fail");
            //addtorepository();
        }
        //
        // Evaluate page size. If requested parameter is null, return initial
        // page size
        int evalPageSize = pageSize.orElse(INITIAL_PAGE_SIZE);
        // Evaluate page. If requested parameter is null or less than 0 (to
        // prevent exception), return initial size. Otherwise, return value of
        // param. decreased by 1.
        int evalPage = (page.orElse(0) < 1) ? INITIAL_PAGE : page.get() - 1;
        // print repo
//        System.out.println("here is client repo " + repository.findAll());
        PageRequest pr = PageRequest.of(evalPage, evalPageSize);
        Page<Appointment> clientlist = repository.findAll(pr);
//        System.out.println("client list get total pages" + clientlist.getTotalPages() + "client list get number " + clientlist.getNumber());
        PagerModel pager = new PagerModel(clientlist.getTotalPages(),clientlist.getNumber(),BUTTONS_TO_SHOW);
        // add clientmodel
        modelAndView.addObject("clientlist",clientlist);
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
//    public void addtorepository(){
//        //below we are adding clients to our repository for the sake of this example
//    	AppointmentDashboardDTO widget = new AppointmentDashboardDTO();
//                widget.setAddress("123 Fake Street");
////                widget.setCurrentInvoice(10000);
//                widget.setName("Widget Inc");
//                clientrepository.save(widget);
//                //next client
//                ClientModel foo = new ClientModel();
//                foo.setAddress("456 Attorney Drive");
//                foo.setCurrentInvoice(20000);
//                foo.setName("Foo LLP");
//                clientrepository.save(foo);
//                //next client
//                ClientModel bar = new ClientModel();
//                bar.setAddress("111 Bar Street");
//                bar.setCurrentInvoice(30000);
//                bar.setName("Bar and Food");
//                clientrepository.save(bar);
//                //next client
//                ClientModel dog = new ClientModel();
//                dog.setAddress("222 Dog Drive");
//                dog.setCurrentInvoice(40000);
//                dog.setName("Dog Food and Accessories");
//                clientrepository.save(dog);
//                //next client
//                ClientModel cat = new ClientModel();
//                cat.setAddress("333 Cat Court");
//                cat.setCurrentInvoice(50000);
//                cat.setName("Cat Food");
//                clientrepository.save(cat);
//                //next client
//                ClientModel hat = new ClientModel();
//                hat.setAddress("444 Hat Drive");
//                hat.setCurrentInvoice(60000);
//                hat.setName("The Hat Shop");
//                clientrepository.save(hat);
//                //next client
//                ClientModel hatB = new ClientModel();
//                hatB.setAddress("445 Hat Drive");
//                hatB.setCurrentInvoice(60000);
//                hatB.setName("The Hat Shop B");
//                clientrepository.save(hatB);
//                //next client
//                ClientModel hatC = new ClientModel();
//                hatC.setAddress("446 Hat Drive");
//                hatC.setCurrentInvoice(60000);
//                hatC.setName("The Hat Shop C");
//                clientrepository.save(hatC);
//                //next client
//                ClientModel hatD = new ClientModel();
//                hatD.setAddress("446 Hat Drive");
//                hatD.setCurrentInvoice(60000);
//                hatD.setName("The Hat Shop D");
//                clientrepository.save(hatD);
//                //next client
//                ClientModel hatE = new ClientModel();
//                hatE.setAddress("447 Hat Drive");
//                hatE.setCurrentInvoice(60000);
//                hatE.setName("The Hat Shop E");
//                clientrepository.save(hatE);
//                //next client
//                ClientModel hatF = new ClientModel();
//                hatF.setAddress("448 Hat Drive");
//                hatF.setCurrentInvoice(60000);
//                hatF.setName("The Hat Shop F");
//                clientrepository.save(hatF);
//    }

}