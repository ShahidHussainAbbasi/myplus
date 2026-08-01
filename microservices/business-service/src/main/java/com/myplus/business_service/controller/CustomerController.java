package com.myplus.business_service.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.enums.CustomerType;
import com.myplus.business_service.service.ICustomerService;
// import com.myplus.business_service.service.ICustomerService;
import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;

@Controller
public class CustomerController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	ICustomerService customerService;

	@Autowired
	com.myplus.business_service.service.StoreCreditService storeCreditService;   // SF-5 Model B: store-credit balance

	@Autowired
	com.myplus.business_service.service.PartyBridgeService partyBridgeService;   // P1: shared party master bridge

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();

	private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getUserId(); }
	/** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
	private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getOrganizationId(); }
	/** Role-aware visibility: owner/super AND admin see the whole org's customers; a plain user only their own. */
	private boolean seesAllOrg() {
		return requestUtil.callerSeesWholeOrg();
	}
	/** Org-wide for SUPER, own-only for everyone else. */
	private List<Customer> visibleCustomers() {
		return seesAllOrg() ? customerService.findScoped(orgId(), userId())
		                    : customerService.findOwnScoped(orgId(), userId());
	}

	@RequestMapping(value = "/getUserCustomer", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserCustomer(@RequestParam(required=false) Integer page,
			@RequestParam(required=false) Integer size, final HttpServletRequest request) {
		try {
			// optional pagination (slice 24): when page&size are sent return that page; else full list (UI contract).
			// Role-aware: the paged org-wide query is ONLY for whole-org viewers (owner/super/admin); a plain
			// user always gets their OWN rows (previously this branch leaked the whole org to any user).
			List<Customer> objs = (page != null && size != null && seesAllOrg())
					? customerService.findScoped(orgId(), userId(), org.springframework.data.domain.PageRequest.of(page, size))
					: visibleCustomers();   // role-aware: whole-org viewers = org, others = own
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<CustomerDTO> dtos=new ArrayList<CustomerDTO>(); 
			objs.forEach(obj ->{
				modelMapper.addConverter(appUtil.localDateToString);
				modelMapper.addConverter(appUtil.localDateTimeToString);
				CustomerDTO dto = modelMapper.map(obj, CustomerDTO.class);
				// dto.setDatedStr(appUtil.getLocalDateTimeStr(obj.getDated()));
				// dto.setUpdatedStr(appUtil.getLocalDateTimeStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > getUserCustomer "+e.getCause(), e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/getUserCustomers", method = RequestMethod.GET) 
	@ResponseBody
	public String getUserCustomers(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			List<Customer> objs = visibleCustomers();   // role-aware: SUPER = org, others = own

			objs.forEach(d -> {
				sb.append("<option value="+d.getCustomerId()+">"+d.getName()+"</option>");
			});
		    return sb.toString();
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > getUserCustomers "+e.getCause(), e);			
			return (sb.append("<option value=''>No Data found</option>")).toString();
		}
	}

	@RequestMapping(value = "/getAllCustomer", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllCustomer(final HttpServletRequest request) {
		try {
			// was findAll() — cross-tenant leak; now scoped to the active org.
			List<Customer> objs = visibleCustomers();   // role-aware: SUPER = org, others = own
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > getAllCustomer "+e.getCause(), e);			
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/addCustomer", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addOwner(@Validated final CustomerDTO dto, final HttpServletRequest request) {
		try {
			Customer obj= new Customer();
			LocalDateTime dated = LocalDateTime.now();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			dto.setUserId(user.getUserId());

			// dup-name check among the creator's OWN customers (per-user model: two users may each
			// have a customer of the same name; SUPER aggregates but still creates under itself).
			if(appUtil.isEmptyOrNull(dto.getCustomerId())){
				boolean exists = customerService.findOwnScoped(orgId(), userId()).stream()
						.anyMatch(c -> c.getName()!=null && c.getName().equalsIgnoreCase(dto.getName()));
				if(exists) {
					return new GenericResponse("FOUND", "Customer '" + dto.getName() + "' already exists.");
				}
			}

			obj = modelMapper.map(dto, Customer.class);
			//if it is update
			if(!appUtil.isEmptyOrNull(dto.getCustomerId())) {
				Customer existing = customerService.findById(dto.getCustomerId()).orElse(null);
				if(existing != null) {
					obj.setDated(existing.getDated());
					// dueAmount + dueDate are DERIVED (owned by recomputeDue / Receive Payment) — a profile edit
					// carries a blank due from the form, so preserve the real balance instead of wiping it.
					obj.setDueAmount(existing.getDueAmount());
					obj.setDueDate(existing.getDueDate());
					// B2B-P0: an edit that does not carry the channel must not silently demote a trade account
					// back to walk-in. Only a value the caller actually sent may change it.
					if (dto.getCustomerType() == null) obj.setCustomerType(existing.getCustomerType());
				}
			}else {
				obj.setDated(dated);
			}
			obj.setUpdated(dated);
			obj.setUserId(user.getUserId());                  // audit: who created it
			obj.setOrganizationId(user.getOrganizationId());  // tenant scope
			// B2B-P0: the channel is never left unknown. V29 backfilled every existing row to WALK_IN, so a new
			// customer saved from a caller that does not send the field (the older form, an integration) must land
			// on the same value — otherwise "no type" would mean WALK_IN for old rows and NULL for new ones, and
			// every consumer would need its own null rule.
			obj.setCustomerType(CustomerType.orDefault(obj.getCustomerType()));
			obj = customerService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED", "Failed to save customer. Please try again.");
			}else {
				partyBridgeService.bridgeCustomer(obj);   // P1: link to the shared party master (best-effort)
				return new GenericResponse("SUCCESS", "Customer saved successfully.");
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > addCustomer "+e.getCause(), e);
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
		}
	}
	
	/** SF-5 Model B: the customer's redeemable store-credit balance (for the checkout "apply store credit" UI). */
	@RequestMapping(value = "/customerCredit", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse customerCredit(@RequestParam Long customerId) {
		try {
			return new GenericResponse("SUCCESS", "Store credit", storeCreditService.balance(customerId));
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > customerCredit " + e.getMessage(), e);
			return new GenericResponse("ERROR", "Could not load store credit.");
		}
	}

	/** Receive Payment (AR subledger): FIFO-allocate a receipt to the customer's open invoices, recompute their
	 *  due, and record it in the shared finance ledger. Returns {receiptNo, allocated, onAccountCredit, newDue}. */
	@RequestMapping(value = "/receivePayment", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse receivePayment(@RequestParam Long customerId, @RequestParam java.math.BigDecimal amount,
			@RequestParam(required = false) String method, @RequestParam(required = false) String paidOn,
			@RequestParam(required = false) String reference,
			@RequestParam(required = false) String idempotencyKey) {
		try {
			java.time.LocalDate on = appUtil.isEmptyOrNull(paidOn) ? java.time.LocalDate.now() : appUtil.toLocalDateOrNull(paidOn);
			if (on == null) on = java.time.LocalDate.now();
			java.util.Map<String, Object> res = customerService.receivePayment(customerId, amount, method, on, reference, idempotencyKey);
			return new GenericResponse("SUCCESS", "Payment received.", res);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > receivePayment " + e.getCause(), e);
			return new GenericResponse("ERROR", e.getMessage() != null ? e.getMessage() : "Failed to record payment.");
		}
	}

	@PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
	@RequestMapping(value = "/deleteCustomer", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteCustomer(HttpServletRequest req, HttpServletResponse resp) {
		try {
			String ids = req.getParameter("checked");
			if (!appUtil.isEmptyOrNull(ids)) {
				String[] idList = ids.split(",");
				for (String id : idList) {
					Long cid = Long.valueOf(id);
					Customer existing = customerService.findById(cid).orElse(null);
					if (existing == null) continue;
					// anti-IDOR: only delete rows in the caller's tenant (or their pre-migration org-NULL rows)
					boolean ownTenant = (existing.getOrganizationId() != null && existing.getOrganizationId().equals(orgId()))
							|| (existing.getOrganizationId() == null && existing.getUserId() != null && existing.getUserId().equals(userId()));
					if (ownTenant) customerService.deleteById(cid);
				}
				return true;
			} else {
				return false;
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > deleteCustomer " + e.getCause(), e);
			return false;
		}
	}

}