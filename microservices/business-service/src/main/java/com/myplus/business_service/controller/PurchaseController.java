package com.myplus.business_service.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.service.ICompanyService;
// import com.myplus.business_service.service.IItemTypeService;
import com.myplus.business_service.service.IItemUnitService;
import com.myplus.business_service.service.IPurchaseService;
import com.myplus.business_service.service.IVenderService;
import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.dto.StockDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;

@Controller
public class PurchaseController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	IPurchaseService purchaseService;
	
	@Autowired
	ICompanyService companyService;

	// @Autowired
	// IItemTypeService itemTypeService;

	@Autowired
	IItemUnitService itemUnitService;

	@Autowired
	IVenderService venderService;

	@Autowired
	com.myplus.commerce.contracts.client.CatalogClient catalogClient;   // M4d: resolve line names from catalog

	/** M4d (slice 96): batch-resolve catalog ProductRef by productId for the read grid (name/sku); best-effort. */
	private java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> productRefs(java.util.List<Long> productIds) {
		if (productIds == null || productIds.isEmpty()) return java.util.Collections.emptyMap();
		try {
			return catalogClient.getProducts(productIds).stream()
				.collect(java.util.stream.Collectors.toMap(com.myplus.commerce.contracts.dto.ProductRef::getId, p -> p, (a, b) -> a));
		} catch (Exception e) {
			LOGGER.warn("M4d: catalog getProducts failed for {} id(s); purchase line names may be blank", productIds.size(), e);
			return java.util.Collections.emptyMap();
		}
	}

	@Autowired
	RequestUtil requestUtil;

    @Autowired
    private AppUtil appUtil;  
    
	ModelMapper modelMapper = new ModelMapper();

	private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getUserId(); }
	/** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
	private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getOrganizationId(); }
	private boolean inMyTenant(Long rowOrg, Long rowUser) {
		return (rowOrg != null && rowOrg.equals(orgId()))
			|| (rowOrg == null && rowUser != null && rowUser.equals(userId()));
	}
	/** Role-aware visibility: SUPER/owner sees the whole org's purchases; others only their own. */
	private boolean seesAllOrg() {
		AuthenticatedUser u = requestUtil.getCurrentUser();
		return u != null && u.getAuthorities() != null && u.getAuthorities().stream()
				.anyMatch(a -> "SUPER_PRIVILEGE".equals(a.getAuthority()));
	}
	private List<Purchase> visiblePurchases() {
		return seesAllOrg() ? purchaseService.findScoped(orgId(), userId())
		                    : purchaseService.findOwnScoped(orgId(), userId());
	}

	@RequestMapping(value = "/getUserPurchase", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserPurchase(final HttpServletRequest request) {
		try {
			List<Purchase> objs = visiblePurchases();   // role-aware: SUPER = org, others = own
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			// M4d (slice 96): batch-resolve line names from catalog ProductRef (no Item entity load). The purchase
			// carries its own itemId + productId, so no reverse map is needed.
			java.util.List<Long> pProductIds = objs.stream().filter(o -> o.getProductId() != null)
					.map(com.myplus.business_service.entity.Purchase::getProductId).distinct()
					.collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> productById = productRefs(pProductIds);

			List<PurchaseDTO> dtos=new ArrayList<PurchaseDTO>();
			objs.forEach(o ->{
				modelMapper.addConverter(appUtil.localDateTimeToString);
				modelMapper.addConverter(appUtil.localDateToString);
				PurchaseDTO dto = modelMapper.map(o, PurchaseDTO.class);

				// M4d (slice 96): identity from the purchase's own fields; name/sku from catalog ProductRef (no Item load).
				Long itemId = o.getItemId();
				if (itemId == null && o.getProductId() == null) return;   // truly unidentifiable line
				dto.setItemId(itemId);
				com.myplus.commerce.contracts.dto.ProductRef p = (o.getProductId() != null) ? productById.get(o.getProductId()) : null;
				if (p != null) {
					dto.setIname(p.getName());
					dto.setIcode(p.getSku());
				}

				StockDTO sd = new StockDTO();   // the UI grid's nested batch/rate contract, built from the purchase
				sd.setBatchNo(o.getBatchNo());
				sd.setBpurchaseRate(o.getBpurchaseRate());
				sd.setBsellRate(o.getBsellRate());
				sd.setBpurchaseDiscount(o.getBpurchaseDiscount());
				sd.setBsellDiscount(o.getBsellDiscount());
				sd.setBpurchaseDiscountType(o.getBpurchaseDiscountType());
				sd.setBsellDiscountType(o.getBsellDiscountType());
				sd.setBexpDate(o.getBexpDate() != null ? o.getBexpDate().toString() : null);
				sd.setStock(o.getQuantity());                 // the purchased quantity
				dto.setStock(sd);
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > getUserPurchase "+e.getCause(), e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/getAllPurchase", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllPurchase(final HttpServletRequest request) {
		try {
			// was findAll() — cross-tenant leak; now scoped to the active org.
			List<Purchase> objs = visiblePurchases();   // role-aware: SUPER = org, others = own
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<PurchaseDTO> dtos=new ArrayList<PurchaseDTO>();
			objs.forEach(obj ->{
				PurchaseDTO dto = modelMapper.map(obj, PurchaseDTO.class);
//				dto.setItemUnitId(obj.getItemUnit().getId());
//				dto.setItemUnitName(obj.getItemUnit().getName());
//				dto.setItemTypeId(obj.getItemType().getId());
//				dto.setItemTypeName(obj.getItemType().getName());
//				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
//				dto.setUpdated(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > getAllPurchase "+e.getCause(), e);			
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/addPurchase", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addPurchase(@Validated final PurchaseDTO dto, final HttpServletRequest request) {
		try {
			if(appUtil.isEmptyOrNull(purchaseService.addPurchase(dto))) {
				if(appUtil.isEmptyOrNull(dto.getPurchaseId())) {
					return new GenericResponse("FAILED", "Failed to save purchase. Please try again.");
				}else {
					return new GenericResponse("FAILED", "Failed to update purchase. Please try again.");
				}
			}else {
				if(appUtil.isEmptyOrNull(dto.getPurchaseId())) {
					return new GenericResponse("SUCCESS", "Purchase saved successfully.");
				}else {
					return new GenericResponse("SUCCESS", "Purchase updated successfully.");
				}
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > addPurchase "+e.getCause(), e);
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
		}
	}
	
	@RequestMapping(value = "/deletePurchase", method = RequestMethod.POST)
	@ResponseBody
	public boolean deletePurchase( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					Long pid = Long.valueOf(id);
					Purchase existing = purchaseService.findById(pid).orElse(null);
					if(existing == null) continue;
					if(inMyTenant(existing.getOrganizationId(), existing.getUserId())) // anti-IDOR
						purchaseService.deleteById(pid);
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > deletePurchase "+e.getCause(), e);			
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
