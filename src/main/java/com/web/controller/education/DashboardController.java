package com.web.controller.education;

import java.math.BigInteger;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.service.education.IDashboardService;
import com.web.dto.education.DashboardDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
public class DashboardController {

//	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	IDashboardService service;
	
	@Autowired
	RequestUtil requestUtil;

	@RequestMapping(value = "/getDashboardData", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getDashboardData(final HttpServletRequest request) {
		try {
			DashboardDTO dto = DashboardDTO.builder().lastMonth(appUtil.getLocalDateForDBStr(appUtil.dateOfLastMonth(1))).build();
	    	Object obj = service.getDashboardData(dto.getLastMonth(),requestUtil.getCurrentUser().getId());//"2019-11-01"
			if(appUtil.isEmptyOrNull(obj))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),obj);

			Object[] line =  (Object[]) obj;
	    	dto.setAllStudent(((BigInteger)line[1]).longValue());
	    	dto.setFreshStudent(((BigInteger)line[0]).longValue());
			
			return new GenericResponse("SUCCESS",dto);
		} catch (Exception e) {
			log.error(this.getClass().getName() + " > getDashboardData > "+e.getCause());
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}

}
