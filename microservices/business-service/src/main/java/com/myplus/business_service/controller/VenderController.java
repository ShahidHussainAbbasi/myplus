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
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Company;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.service.ICompanyService;
import com.myplus.business_service.service.IVenderService;
import com.myplus.business_service.dto.VenderDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;

@Controller
public class VenderController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	IVenderService venderService;

	@Autowired
	com.myplus.business_service.service.PartyBridgeService partyBridgeService;   // P1: shared party master bridge
	
	@Autowired
	ICompanyService companyService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();

	private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getUserId(); }
	/** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
	private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getOrganizationId(); }
	private boolean inMyTenant(Long rowOrg, Long rowUser) {
		return (rowOrg != null && rowOrg.equals(orgId()))
			|| (rowOrg == null && rowUser != null && rowUser.equals(userId()));
	}

	@RequestMapping(value = "/getUserVender", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserVender(final HttpServletRequest request) {
		try {
			List<Vender> objs = venderService.findScoped(orgId(), userId());
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<VenderDTO> dtos=new ArrayList<VenderDTO>(); 
			objs.forEach(obj ->{
				VenderDTO dto = modelMapper.map(obj, VenderDTO.class);
				fillCompanies(dto, obj);
				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > getUserVender "+e.getCause(), e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/getUserVenders", method = RequestMethod.GET)
	@ResponseBody
	public String getUserVenders(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			List<Vender> objs = venderService.findScoped(orgId(), userId());

			// objs.forEach(d -> {
			// 	if(d!=null && d.getId()!=null) {
			// 		sb.append("<option value="+d.getId()+">"+d.getName()+"</option>");
			// 	}
			// });
		    // return sb.toString();

			sb.append("<option value=''>Nothing Selected</option>");
			objs.forEach(d -> {
				// B2B-P0 (#8): carry the vendor's outstanding payable on the option, exactly as the customer
				// dropdown carries data-due. The purchase screen reads it on select to show what this vendor
				// is already owed BEFORE more credit is taken on — no extra round trip per selection.
				java.math.BigDecimal due = d.getDueAmount() == null ? java.math.BigDecimal.ZERO : d.getDueAmount();
				sb.append("<option value=" + d.getId() + " data-due=\"" + due.toPlainString() + "\">")
				  .append(d.getName())
				  .append("</option>");
			});
			return sb.toString();

		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > getUserVenders "+e.getCause(), e);			
			return (sb.append("<option value=''>No Data found</option>")).toString();
		}
	}

	@RequestMapping(value = "/getAllVender", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllVender(final HttpServletRequest request) {
		try {
			// was findAll() — cross-tenant leak; now scoped to the active org.
			List<Vender> objs = venderService.findScoped(orgId(), userId());
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<VenderDTO> dtos=new ArrayList<VenderDTO>();
			objs.forEach(obj ->{
				VenderDTO dto = modelMapper.map(obj, VenderDTO.class);
				fillCompanies(dto, obj);
				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > getAllVender "+e.getCause(), e);			
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/addVender", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addOwner(@Validated final VenderDTO dto, final HttpServletRequest request) {
		try {
			Vender obj= new Vender();
			LocalDateTime dated = LocalDateTime.now();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			dto.setUserId(user.getUserId());
			obj.setUserId(user.getUserId());
			if(appUtil.isEmptyOrNull(dto.getId())){
				// dup-name check within the active tenant (was a userId-only Example probe)
				boolean exists = venderService.findScoped(orgId(), userId()).stream()
						.anyMatch(v -> v.getName()!=null && v.getName().equalsIgnoreCase(dto.getName()));
				if(exists)
					return new GenericResponse("FOUND", "Vender '" + dto.getName() + "' already exists.");
			}

			obj = modelMapper.map(dto, Vender.class);
			//if it is update
			if(!appUtil.isEmptyOrNull(dto.getId())) {
				Vender existing = venderService.findById(dto.getId()).orElse(null);
				if(existing != null) {
					obj.setDated(existing.getDated());
					obj.setDueAmount(existing.getDueAmount());   // F1 (AP): a profile edit must not wipe the payable
				}
			}else {
				obj.setDated(dated);
			}
			obj.setUpdated(dated);
			obj.setUserId(user.getUserId());                  // audit
			obj.setOrganizationId(user.getOrganizationId());  // tenant scope

			// The plural field wins; the singular one is still honoured so existing callers keep working.
			String brandCsv = (dto.getCompanyIds() != null && !dto.getCompanyIds().isBlank())
					? dto.getCompanyIds()
					: (dto.getCompanyId() == null ? null : String.valueOf(dto.getCompanyId()));

			// At least one brand, refused in words the operator can act on. The form marks the field required,
			// and a server that silently accepted an empty set would let a stale page create a supplier that
			// represents nobody — invisible on every screen that groups by brand.
			if (brandCsv == null || brandCsv.isBlank()) {
				return new GenericResponse("FAILED", "Choose at least one company or distributor for this vendor.");
			}
			applyCompanies(obj, brandCsv);
			if (obj.getCompanies().isEmpty()) {
				return new GenericResponse("FAILED", "None of the selected companies could be found. Reload the page and try again.");
			}
			obj = venderService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED", "Failed to save vender. Please try again.");
			}else {
				partyBridgeService.bridgeVender(obj);   // P1: link to the shared party master (best-effort)
				return new GenericResponse("SUCCESS", "Vender saved successfully.");
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > addVender "+e.getCause(), e);
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
		}
	}
	
	/**
	 * F1 (AP): pay a vendor. FIFO-allocates the amount across the vendor's open purchase bills, recomputes the
	 * vendor payable, and records a DISBURSEMENT in the shared finance ledger. Mirrors CustomerController.receivePayment.
	 */
	@RequestMapping(value = "/payVendor", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse payVendor(final HttpServletRequest request) {
		try {
			String vId = request.getParameter("venderId");
			String amt = request.getParameter("amount");
			if (appUtil.isEmptyOrNull(vId) || appUtil.isEmptyOrNull(amt))
				return new GenericResponse("FAILED", "Vendor and amount are required.");
			Long venderId = Long.valueOf(vId.trim());
			// anti-IDOR: the vendor must belong to the caller's tenant
			Vender vendor = venderService.findById(venderId).orElse(null);
			if (vendor == null || !inMyTenant(vendor.getOrganizationId(), vendor.getUserId()))
				return new GenericResponse("NOT_FOUND", "Vendor not found.");
			java.math.BigDecimal amount = new java.math.BigDecimal(amt.trim());
			String method = request.getParameter("method");
			String reference = request.getParameter("reference");
			String paidOnStr = request.getParameter("paidOn");
			java.time.LocalDate paidOn = appUtil.isEmptyOrNull(paidOnStr) ? null : java.time.LocalDate.parse(paidOnStr.trim());
			String idempotencyKey = request.getParameter("idempotencyKey");
			java.util.Map<String, Object> result = venderService.payVendor(venderId, amount, method, paidOn, reference, idempotencyKey);
			return new GenericResponse("SUCCESS", "Payment recorded.", result);
		} catch (NumberFormatException nfe) {
			return new GenericResponse("FAILED", "Invalid amount.");
		} catch (com.myplus.business_service.service.PeriodClosedException pce) {
			LOGGER.warn("payVendor rejected (period closed): {}", pce.getMessage());
			return new GenericResponse("FAILED", pce.getMessage());
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > payVendor "+e.getCause(), e);
			return new GenericResponse("FAILED", "An unexpected error occurred. Please contact support.");
		}
	}

	@PreAuthorize("hasAuthority('DELETE_VENDER')")
	@RequestMapping(value = "/deleteVender", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteVender( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					Long vid = Long.valueOf(id);
					Vender existing = venderService.findById(vid).orElse(null);
					if(existing == null) continue;
					if(inMyTenant(existing.getOrganizationId(), existing.getUserId())) // anti-IDOR
						venderService.deleteById(vid);
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > deleteVender "+e.getCause(), e);			
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}

	// ── brands a supplier represents ──────────────────────────────────────────────────────────────────

	/**
	 * Fill the DTO's brand fields: ids for the form to round-trip, names for the grid to show.
	 *
	 * <p>Both in ONE place because they were previously written out twice, in two loops, and the second copy
	 * used a different null check than the first. Two copies of a mapping are two chances to disagree.
	 */
	private void fillCompanies(VenderDTO dto, Vender obj) {
		if (obj.getCompanies() == null || obj.getCompanies().isEmpty()) return;

		StringBuilder ids = new StringBuilder(), names = new StringBuilder();
		for (Company c : obj.getCompanies()) {
			if (c == null || c.getId() == null) continue;   // @NotFound(IGNORE) can leave a hole
			if (ids.length() > 0) { ids.append(','); names.append(", "); }
			ids.append(c.getId());
			names.append(c.getName() == null ? "" : c.getName());
		}
		dto.setCompanyIds(ids.toString());
		dto.setCompanyNames(names.toString());
	}

	/**
	 * Replace this supplier's brands with exactly the set submitted.
	 *
	 * <p>REPLACE, not merge: the form shows the current set and the operator edits it, so an unticked brand
	 * means "no longer represents them". Merging would make removal impossible from the only screen that
	 * offers it.
	 *
	 * <p>Unparseable or unknown ids are skipped rather than failing the save — the ids come from a select the
	 * server itself populated, so a bad one means a stale page, and losing a supplier's whole record over it
	 * would be a poor trade. An empty result is refused by the caller.
	 *
	 * @param csv comma-separated company ids; see {@code VenderDTO.companyIds} for why it is a string
	 */
	private void applyCompanies(Vender obj, String csv) {
		java.util.Set<Company> chosen = new java.util.LinkedHashSet<>();
		if (csv != null && !csv.isBlank()) {
			for (String raw : csv.split(",")) {
				String id = raw.trim();
				if (id.isEmpty()) continue;
				try {
					chosen.add(companyService.getReferenceById(Long.valueOf(id)));
				} catch (NumberFormatException ignoredStalePage) {
					LOGGER.warn("Ignoring unparseable company id '{}' on a vendor save", id);
				}
			}
		}
		obj.getCompanies().clear();
		obj.getCompanies().addAll(chosen);
	}
}
