package com.web.controller.education;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
import com.persistence.model.education.School;
import com.persistence.model.education.Vehicle;
import com.service.education.ISchoolService;
import com.service.education.IVehicleService;
import com.web.dto.education.VehicleDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class VehicleController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	IVehicleService vehicleService;

	@Autowired
	ISchoolService schoolService;
//	@Autowired
//	AppUtil appUtil;

	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();

	@RequestMapping(value = "/getUserVehicle", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserVehicle(final HttpServletRequest request) {
		try {
			Vehicle filterBy = new Vehicle();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
			Example<Vehicle> example = Example.of(filterBy);
			List<Vehicle> objs = vehicleService.findAll(example);
			if (AppUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",
						messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<VehicleDTO> dtos = new ArrayList();
			objs.forEach(obj -> {
				VehicleDTO dto = modelMapper.map(obj, VehicleDTO.class);
				if(!AppUtil.isEmptyOrNull(obj.getSchoolId())) {
					School school = schoolService.getOne(dto.getSchoolId());
					if (!AppUtil.isEmptyOrNull(school)) {
						dto.setSchoolId(school.getId());
						dto.setSchoolName(school.getBranchName());
					}
				}
				dto.setDatedStr(AppUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(AppUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",
					messages.getMessage("message.userNotFound", null, request.getLocale()), dtos);
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/getUserVehicles", method = RequestMethod.GET)
	@ResponseBody
	public String getUserVehicles(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Vehicle filterBy = new Vehicle();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
			Example<Vehicle> example = Example.of(filterBy);
			List<Vehicle> objs = vehicleService.findAll(example);
			objs.forEach(d -> {
				if (d != null && d.getId() != null) {
					sb.append("<option value=" + d.getId() + ">" + d.getName() + "</option>");
				}
			});
			return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return sb.toString();
	}

	@RequestMapping(value = "/getAllVehicle", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllVehicle(final HttpServletRequest request) {
		try {
			List<Vehicle> objs = vehicleService.findAll();
			List<VehicleDTO> dtos = new ArrayList();
			objs.forEach(obj -> {
				dtos.add(modelMapper.map(obj, VehicleDTO.class));
			});
			if (AppUtil.isEmptyOrNull(objs)) {
				return new GenericResponse("NOT_FOUND",
						messages.getMessage("message.userNotFound", null, request.getLocale()), dtos);
			} else {
				return new GenericResponse("SUCCESS",
						messages.getMessage("message.userNotFound", null, request.getLocale()), dtos);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/addVehicle", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addVehicle(@Validated final VehicleDTO dto, final HttpServletRequest request) {
		try {
			LocalDateTime dated = LocalDateTime.now();
			Vehicle obj = new Vehicle();
			User user = requestUtil.getCurrentUser();
			dto.setUserId(user.getId());
			if (AppUtil.isEmptyOrNull(dto.getId())) {
				obj.setUserId(user.getId());
				obj.setName(dto.getName());
				obj.setSchoolId(dto.getSchoolId());
				Example<Vehicle> example = Example.of(obj);
				if(vehicleService.exists(example))
					return new GenericResponse("FOUND", messages.getMessage("The Vehicle " + dto.getName() + " already exist",
							null, request.getLocale()));
			}

			obj = modelMapper.map(dto, Vehicle.class);
			obj.setDated(dated);
			obj.setUpdated(dated);

			obj = vehicleService.save(obj);
			if (AppUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILURE",
						messages.getMessage("message.userNotFound", null, request.getLocale()));
			} else {
				return new GenericResponse("SUCCESS",
						messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR", messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/deleteVehicle", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteVehicle(HttpServletRequest req, HttpServletResponse resp) {
		try {
			String ids = req.getParameter("checked");
			if (!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for (String id : idList) {
//					vehicleService.deleteById(Long.valueOf(id));
					vehicleService.deleteById(Long.valueOf(id));// .updateStatus("Inactive", id);
				}
				return true;// new GenericResponse(messages.getMessage("message.userNotFound", null,
							// request.getLocale()),"SUCCESS");
			} else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null,
								// request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			e.printStackTrace();
			return false;// new GenericResponse(messages.getMessage("message.userNotFound", null,
							// request.getLocale()),
		}
	}
}
