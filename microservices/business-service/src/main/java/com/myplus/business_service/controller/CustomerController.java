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
import com.myplus.business_service.dto.OutletAssignmentDTO;
import com.myplus.business_service.dto.OutletDTO;
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
	 *
	 * <p><b>And the other half of the same field.</b> Blank was fixed; FILLED IN was not. {@code #customerLicenseExpiry}
	 * carries {@code class="datePicker"}, and that picker's wire format is {@code dd-MM-yyyy} — a documented
	 * contract, not an implementation detail (see {@code /js/common/date-picker.js}). The bare
	 * {@code LocalDate.parse} here is strict ISO, so every customer whose licence expiry was actually entered
	 * failed to save with {@code Text '31-08-2030' could not be parsed at index 0}. The two halves of an
	 * optional field are one behaviour: blank means absent, and anything the picker can emit must bind.
	 *
	 * <p>Delegated to {@link AppUtil#toLocalDateStrict} so the accepted formats have ONE definition shared with
	 * the rest of business-service, rather than a second list drifting from the first. Strict, not lenient: an
	 * unparseable licence expiry is refused rather than stored as null, because it is printed on the invoice as
	 * evidence the buyer may be supplied and an operator would never learn it had been dropped.
	 */
	@org.springframework.web.bind.annotation.InitBinder
	public void initBinder(org.springframework.web.bind.WebDataBinder binder) {
		binder.registerCustomEditor(java.time.LocalDate.class, new java.beans.PropertyEditorSupport() {
			@Override public void setAsText(String text) {
				setValue(appUtil.toLocalDateStrict(text));
			}
		});
	}

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	ICustomerService customerService;

	/*
	 * The repository directly, for the PROJECTION read only.
	 *
	 * ICustomerService extends JpaRepository, not CustomerRepo, so the projection queries are not visible
	 * through it. Everything that deals in entities still goes through the service; this is the one read
	 * that returns a DTO straight from the result set and never builds an entity at all.
	 */
	@Autowired
	com.myplus.business_service.repository.CustomerRepo customerRepo;

	@Autowired
	com.myplus.business_service.service.StoreCreditService storeCreditService;   // SF-5 Model B: store-credit balance

	@Autowired
	com.myplus.business_service.service.PartyBridgeService partyBridgeService;   // P1: shared party master bridge

	@Autowired
	com.myplus.business_service.service.CustomerAccountService customerAccountService;   // P4a: account hierarchy

	@Autowired
	com.myplus.business_service.service.CreditStandingService creditStandingService;   // O7 D2: credit at booking

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

	/**
	 * PERF — the customer list a PICKER needs: six fields, selected, not the whole record.
	 *
	 * <h3>Why this exists next to {@code /getUserCustomer} rather than replacing it</h3>
	 * That read returns 22 fields for 441 rows (~215KB) on every open of the sale screen, unpaginated, and
	 * six of those fields are what the dropdown actually uses. But it is a general-purpose read: forty
	 * Cypress specs consume it, and screens legitimately need the rest of the record. Slimming it because
	 * two of its callers are pickers would break the others to speed those two up — so this is added
	 * alongside, which is the shape PERF-8 used for the product picker.
	 *
	 * <h3>Role-awareness is copied, not re-derived</h3>
	 * Same {@code seesAllOrg()} branch as {@link #getUserCustomer}: a whole-org viewer gets the org's
	 * customers, everyone else only their own. A picker that scoped differently from the master read would
	 * show an operator a customer they cannot otherwise open, or hide one they can — and the screen itself
	 * shows nothing to reveal either.
	 *
	 * <h3>Not paginated, deliberately</h3>
	 * It fills a dropdown, which needs the whole set to be searchable. The fix for size here is projection,
	 * not paging; paging a picker just moves the problem into the operator's way.
	 */
	@RequestMapping(value = "/customerOptions", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse customerOptions(final HttpServletRequest request) {
		try {
			List<com.myplus.business_service.dto.CustomerOptionDTO> options = seesAllOrg()
					? customerRepo.findOptionsScoped(orgId(), userId())
					: customerRepo.findOwnOptionsScoped(orgId(), userId());
			// An empty list is a valid answer for a new tenant — SUCCESS with nothing, not NOT_FOUND. The
			// dropdown renders its placeholder and the operator adds their first customer.
			return new GenericResponse("SUCCESS", "Customer options", null, options);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > customerOptions " + e.getMessage(), e);
			return new GenericResponse("ERROR", "Could not load the customers.");
		}
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

	/**
	 * OMS O7 D2d — the outlets a field rep may book for: the booking screen's picker.
	 *
	 * <h3>Why this is not {@code getUserCustomer}</h3>
	 * That read is role-aware in a way that is correct for the customer MASTER and wrong for a picker: a plain
	 * user sees only rows they created, because {@code Customer.userId} is an audit field the visibility rule
	 * leans on. In a shop the creator and the seller are the same person; in field sales the COMPANY creates
	 * the outlet and a REP sells to it, so an order booker asking for their outlets got back nothing at all.
	 *
	 * <h3>The rule, which is the industry one</h3>
	 * <pre>
	 *   owner / admin            → every outlet in the org
	 *   rep WITH assignments     → their territory (+ unassigned outlets)
	 *   rep with NO assignments  → every outlet in the org
	 * </pre>
	 * Territory is how field sales works everywhere (SAP DSD, Salesforce Territory Management, the SFA products
	 * in this market): a customer list is a distributor's most poachable asset, coverage KPIs are undefined
	 * without an assigned universe, and commission attribution needs to know whose outlet it was.
	 *
	 * <p>The last line is not a loophole — it is this platform's own rule for an absent grant, the one location
	 * scoping already follows ("empty means no constraint"). A distributor who has configured no territories
	 * works on day one; one who assigns them narrows automatically with no code change.
	 *
	 * <p><b>Identity only</b> ({@link OutletDTO}). Balances and limits are not a picker's business — the rep
	 * gets an outlet's credit position from {@code /creditStanding}, one customer at a time, deliberately.
	 */
	@RequestMapping(value = "/outlets", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse outlets(final HttpServletRequest request) {
		try {
			Long org = orgId(), me = userId();
			List<Customer> rows = seesAllOrg()
					? customerService.findOutletsForOrg(org)
					: customerService.findOutletsForRep(org, me);
			List<OutletDTO> out = new ArrayList<>();
			for (Customer c : rows) {
				out.add(new OutletDTO(c.getCustomerId(), c.getName(), c.getContact(), c.getAddress(),
						me != null && me.equals(c.getAssignedRepUserId())));
			}
			return new GenericResponse("SUCCESS", "Outlets", null, out);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > outlets " + e.getMessage(), e);
			return new GenericResponse("ERROR", "Could not load the outlets.");
		}
	}

	/**
	 * OMS O7 D6a — every outlet with the rep who covers it. <b>Owner/admin only.</b>
	 *
	 * <p>Separate from {@code /outlets} on purpose. That one is the REP's picker and answers "which shops may
	 * I book for"; this one is the OWNER's assignment screen and answers "who covers what". Adding the holder
	 * to the rep's picker would tell every rep which colleague owns which shop — a customer list is a
	 * distributor's most poachable asset, and least privilege is why {@link OutletDTO} is identity-only in the
	 * first place.
	 *
	 * <p>Returns the rep's ID, not their name. The screen already loads {@code /api/auth/org/users} to fill
	 * its dropdown, so it can join names itself; resolving them here would put an auth-service call on a read
	 * that exists only to draw a table. Nor is the name STAMPED — unlike {@code booked_by_name}, which is
	 * frozen because an issued order outlives its staff. An assignment is CURRENT state: rename a rep and the
	 * assignment should show the new name.
	 */
	@RequestMapping(value = "/outletAssignments", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse outletAssignments(final HttpServletRequest request) {
		try {
			if (!seesAllOrg())
				return new GenericResponse("ERROR", "Only an owner or admin can view territory assignments.");
			List<OutletAssignmentDTO> out = new ArrayList<>();
			for (Customer c : customerService.findOutletsForOrg(orgId()))
				out.add(new OutletAssignmentDTO(c.getCustomerId(), c.getName(), c.getContact(),
						c.getAssignedRepUserId()));
			return new GenericResponse("SUCCESS", "Outlet assignments", null, out);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > outletAssignments " + e.getMessage(), e);
			return new GenericResponse("ERROR", "Could not load the assignments.");
		}
	}

	/**
	 * OMS O7 D6a — assign outlets to a rep, or clear them. <b>Owner/admin only.</b>
	 *
	 * <h3>Why a rep may not do this</h3>
	 * {@code ROLE_ORDER_BOOKER} exists to WITHHOLD: it is the plain user set with no {@code ADMIN_PRIVILEGE},
	 * precisely so a rep cannot confirm their own orders. Letting one hand themselves outlets would undo the
	 * same separation from the other end.
	 *
	 * <h3>Bulk, because a territory is not one shop</h3>
	 * Assigning one at a time would make the screen fire fifty requests and leave a half-applied territory
	 * when the twentieth failed. One call, one statement.
	 *
	 * <h3>What the count means</h3>
	 * The repository scopes by tenant inside its WHERE clause, so ids belonging to another org are simply not
	 * updated. Reporting the rows ACTUALLY changed — rather than echoing back how many ids were sent — is what
	 * makes a partially-ignored request visible instead of silently successful.
	 *
	 * @param repUserId the rep to assign to; <b>omit or send blank to UNASSIGN</b>, returning the outlets to
	 *                  the shared pool rather than hiding them from everybody
	 */
	@RequestMapping(value = "/assignOutlets", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse assignOutlets(@RequestParam(value = "repUserId", required = false) Long repUserId,
	                                     @RequestParam("customerIds") List<Long> customerIds,
	                                     final HttpServletRequest request) {
		try {
			if (!seesAllOrg())
				return new GenericResponse("ERROR", "Only an owner or admin can assign territories.");
			if (customerIds == null || customerIds.isEmpty())
				return new GenericResponse("ERROR", "Choose at least one outlet.");

			int changed = customerService.assignOutlets(customerIds, repUserId, orgId(), userId());
			java.util.Map<String, Object> body = new java.util.HashMap<>();
			body.put("assigned", changed);
			body.put("requested", customerIds.size());
			// Says so out loud when they differ. A bulk write that reports success for input it quietly
			// skipped is how a territory ends up half applied with nobody the wiser.
			String msg = (changed == customerIds.size())
					? (repUserId == null ? "Outlets unassigned." : "Outlets assigned.")
					: ("Assigned " + changed + " of " + customerIds.size() + " outlets.");
			// 3-arg: the counts are an OBJECT, not a collection — GenericResponse puts lists in `collection`
			// and single payloads in `object`, and the client reads them from different fields.
			return new GenericResponse("SUCCESS", msg, body);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > assignOutlets " + e.getMessage(), e);
			return new GenericResponse("ERROR", "Could not save the assignments.");
		}
	}

	/**
	 * OMS O7 D2 — the outlet's CREDIT STANDING, for an order booker standing at the counter.
	 *
	 * <p>Deliberately named apart from {@code /customerCredit} above, which is the SF-5 store-credit balance —
	 * money the shop is holding FOR the customer. This is the opposite: what the customer owes the shop and how
	 * much room is left against their limit. Two different numbers with confusable names, so the names are
	 * kept unconfusable.
	 *
	 * <p>Answers with {@code null} when the customer is uncapped, which the caller must render as "no limit"
	 * rather than as zero — see {@code CreditStandingService.standingFor}.
	 */
	@RequestMapping(value = "/creditStanding", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse creditStanding(@RequestParam Long customerId) {
		try {
			return new GenericResponse("SUCCESS", "Credit standing", creditStandingService.standingFor(customerId));
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > creditStanding " + e.getMessage(), e);
			return new GenericResponse("ERROR", "Could not load the credit standing.");
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