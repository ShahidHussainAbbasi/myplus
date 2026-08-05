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

	/**
	 * B2B-P3g: bind an EMPTY date field to null instead of failing conversion.
	 *
	 * <p>The customer form now carries an optional {@code licenseExpiry}. A form always posts every input it
	 * owns, so an owner who leaves it blank submits {@code licenseExpiry=} — and the default String→LocalDate
	 * parse of an empty value is a binding error, which would have rejected EVERY customer save for every
	 * tenant, whether or not they use licences. Made explicit here rather than trusted to framework
	 * defaults, because the failure mode is a total outage of a core screen and the cause would read as
	 * "customer save is broken" with nothing pointing at a field nobody filled in.
	 */
	@org.springframework.web.bind.annotation.InitBinder
	public void initBinder(org.springframework.web.bind.WebDataBinder binder) {
		binder.registerCustomEditor(java.time.LocalDate.class, new java.beans.PropertyEditorSupport() {
			@Override public void setAsText(String text) {
				setValue((text == null || text.isBlank()) ? null : java.time.LocalDate.parse(text.trim()));
			}
		});
	}

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
	com.myplus.business_service.service.CustomerAccountService customerAccountService;   // P4a: account hierarchy

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
				// B2B-P4a: a new customer is its OWN credit account until an owner groups it. The stamp owns a
				// transaction in the service (it is a @Modifying update) rather than running inline here.
				//
				// Contained deliberately: registering a customer is a core POS operation that predates B2B
				// grouping and must keep working whatever state this feature is in — an unapplied migration or an
				// unwired party-service must not stop a shop adding a customer. An unstamped row falls back to its
				// own limit in the credit check (never to "no limit") and is re-stamped on the next group edit.
				try {
					customerAccountService.stampSelfAsCreditAccount(obj);
				} catch (Exception stampFailed) {
					LOGGER.warn("Customer {} saved but its credit account could not be stamped", obj.getCustomerId(), stampFailed);
				}
				partyBridgeService.bridgeCustomer(obj);   // P1: link to the shared party master (best-effort)
				return new GenericResponse("SUCCESS", "Customer saved successfully.");
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > addCustomer "+e.getCause(), e);
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
		}
	}
	
	// ── B2B Phase 4a — account hierarchy ────────────────────────────────────────────────────────────────────────

	/**
	 * Put a customer under a parent account, or detach it (omit {@code parentCustomerId}). Sets the parent in
	 * party-service and re-stamps the credit account across every affected row in ONE operator action.
	 *
	 * <p>Owner/admin-gated: restructuring accounts decides whose credit limit governs whose purchases — a
	 * commercial decision, not a counter operation. A guard rejection returns its reason verbatim so the operator
	 * sees why ("that would make the account a descendant of itself"), not a generic failure.
	 */
	@PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
	@RequestMapping(value = "/setCustomerAccountParent", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse setCustomerAccountParent(@RequestParam Long customerId,
	                                                @RequestParam(required = false) Long parentCustomerId,
	                                                @RequestParam(required = false) String accountLevel) {
		try {
			int n = customerAccountService.setAccountParent(customerId, parentCustomerId, accountLevel);
			return new GenericResponse("SUCCESS", "Account updated (" + n + " row(s) re-stamped).", n);
		} catch (IllegalArgumentException e) {
			return new GenericResponse("FAILED", e.getMessage());   // the operator's answer, not a server fault
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > setCustomerAccountParent " + e.getMessage(), e);
			return new GenericResponse("ERROR", "Could not update the account hierarchy.");
		}
	}

	/** The credit group a customer belongs to: the head, everyone drawing on it, the limit and the pooled due. */
	@RequestMapping(value = "/customerAccountGroup", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse customerAccountGroup(@RequestParam Long customerId) {
		try {
			return new GenericResponse("SUCCESS", "Account group", customerAccountService.accountGroup(customerId));
		} catch (IllegalArgumentException e) {
			return new GenericResponse("FAILED", e.getMessage());
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > customerAccountGroup " + e.getMessage(), e);
			return new GenericResponse("ERROR", "Could not load the account group.");
		}
	}

	/** Trade customers with no party link — they cannot join a group, and must be visible rather than omitted. */
	@RequestMapping(value = "/unbridgedCustomers", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse unbridgedCustomers() {
		try {
			return new GenericResponse("SUCCESS", "Unbridged customers", customerAccountService.unbridged());
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > unbridgedCustomers " + e.getMessage(), e);
			return new GenericResponse("ERROR", "Could not load unbridged customers.");
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