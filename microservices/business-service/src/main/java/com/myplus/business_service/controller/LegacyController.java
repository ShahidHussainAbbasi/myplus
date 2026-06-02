// package com.myplus.business_service.controller;

// import com.myplus.business_service.dto.BusinessDTOs.*;
// import com.myplus.business_service.security.AuthenticatedUser;
// import com.myplus.business_service.service.*;
// import lombok.RequiredArgsConstructor;
// import org.springframework.data.domain.PageRequest;
// import org.springframework.data.domain.Pageable;
// import org.springframework.security.core.annotation.AuthenticationPrincipal;
// import org.springframework.web.bind.annotation.*;

// import java.math.BigDecimal;
// import java.util.*;

// /**
//  * Legacy endpoints for the myplus monolith.
//  * Maps old RPC-style URLs to the existing REST services.
//  * Returns GenericResponse-compatible Map format:
//  *   { "status": "SUCCESS", "collection": [...] }
//  *   { "status": "SUCCESS", "object": {...} }
//  *   { "status": "ERROR" }
//  */
// @RestController
// @RequiredArgsConstructor
// public class LegacyController {

//     private final CompanyService  companyService;
//     private final VenderService   venderService;
//     private final CustomerService customerService;
//     private final ItemService     itemService;
//     private final StockService    stockService;
//     private final PurchaseService purchaseService;
//     private final SellService     sellService;
//     private final DashboardService dashboardService;

//     private static final Pageable ALL = PageRequest.of(0, 10_000);

//     // ── helpers ──────────────────────────────────────────────────────────────

//     private Map<String, Object> ok(Collection<?> col) {
//         Map<String, Object> r = new LinkedHashMap<>();
//         r.put("status", "SUCCESS");
//         r.put("collection", col);
//         return r;
//     }

//     private Map<String, Object> ok(Object obj) {
//         Map<String, Object> r = new LinkedHashMap<>();
//         r.put("status", "SUCCESS");
//         r.put("object", obj);
//         return r;
//     }

//     private Map<String, Object> error() {
//         return Collections.singletonMap("status", "ERROR");
//     }

//     private List<Long> parseIds(String checked) {
//         if (checked == null || checked.isBlank()) return List.of();
//         List<Long> ids = new ArrayList<>();
//         for (String s : checked.split(",")) {
//             try { ids.add(Long.valueOf(s.trim())); } catch (NumberFormatException ignored) {}
//         }
//         return ids;
//     }

//     // ── Dashboard ─────────────────────────────────────────────────────────────

//     @GetMapping("/getBusinessDashboardStats")
//     public Map<String, Object> getBusinessDashboardStats(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(dashboardService.getStats(user.getUserId()));
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getDashboardChartData")
//     public Map<String, Object> getDashboardChartData(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(dashboardService.getCharts(user.getUserId()));
//         } catch (Exception e) { return error(); }
//     }

//     // ── Company ───────────────────────────────────────────────────────────────

//     @GetMapping("/getUserCompany")
//     public Map<String, Object> getUserCompany(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(companyService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getAllCompany")
//     public Map<String, Object> getAllCompany(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(companyService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getUserCompanies")
//     public String getUserCompanies(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             StringBuilder sb = new StringBuilder();
//             companyService.getByUser(user.getUserId(), ALL).getContent()
//                 .forEach(c -> sb.append("<option value='").append(c.getId()).append("'>").append(c.getName()).append("</option>"));
//             return sb.toString();
//         } catch (Exception e) { return "<option value=''>No Data found</option>"; }
//     }

//     @PostMapping("/addCompany")
//     public Map<String, Object> addCompany(@RequestParam Map<String, String> params,
//                                           @AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             CompanyDTO dto = new CompanyDTO();
//             dto.setUserId(user.getUserId());
//             dto.setName(params.get("name"));
//             dto.setPhone(params.get("phone"));
//             dto.setEmail(params.get("email"));
//             dto.setAddress(params.get("address"));
//             String id = params.get("id");
//             if (id != null && !id.isBlank())
//                 return ok(companyService.update(Long.valueOf(id), dto));
//             return ok(companyService.create(dto));
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/deleteCompany")
//     public boolean deleteCompany(@RequestParam(required = false) String checked) {
//         try {
//             parseIds(checked).forEach(companyService::delete);
//             return true;
//         } catch (Exception e) { return false; }
//     }

//     // ── Vender ────────────────────────────────────────────────────────────────

//     @GetMapping("/getUserVender")
//     public Map<String, Object> getUserVender(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(venderService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getAllVender")
//     public Map<String, Object> getAllVender(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(venderService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getUserVenders")
//     public String getUserVenders(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             StringBuilder sb = new StringBuilder();
//             venderService.getByUser(user.getUserId(), ALL).getContent()
//                 .forEach(v -> sb.append("<option value='").append(v.getId()).append("'>").append(v.getName()).append("</option>"));
//             return sb.toString();
//         } catch (Exception e) { return "<option value=''>No Data found</option>"; }
//     }

//     @PostMapping("/addVender")
//     public Map<String, Object> addVender(@RequestParam Map<String, String> params,
//                                          @AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             VenderDTO dto = new VenderDTO();
//             dto.setUserId(user.getUserId());
//             dto.setName(params.get("name"));
//             dto.setPhone(params.get("phone"));
//             dto.setMobile(params.get("mobile"));
//             dto.setEmail(params.get("email"));
//             dto.setAddress(params.get("address"));
//             String cid = params.get("companyId");
//             if (cid != null && !cid.isBlank()) dto.setCompanyId(Long.valueOf(cid));
//             String id = params.get("id");
//             if (id != null && !id.isBlank())
//                 return ok(venderService.update(Long.valueOf(id), dto));
//             return ok(venderService.create(dto));
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/deleteVender")
//     public boolean deleteVender(@RequestParam(required = false) String checked) {
//         try {
//             parseIds(checked).forEach(venderService::delete);
//             return true;
//         } catch (Exception e) { return false; }
//     }

//     // ── Customer ──────────────────────────────────────────────────────────────

//     @GetMapping("/getUserCustomer")
//     public Map<String, Object> getUserCustomer(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(customerService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getAllCustomer")
//     public Map<String, Object> getAllCustomer(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(customerService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getUserCustomers")
//     public String getUserCustomers(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             StringBuilder sb = new StringBuilder();
//             customerService.getByUser(user.getUserId(), ALL).getContent()
//                 .forEach(c -> sb.append("<option value='").append(c.getId()).append("'>").append(c.getName()).append("</option>"));
//             return sb.toString();
//         } catch (Exception e) { return "<option value=''>No Data found</option>"; }
//     }

//     @PostMapping("/addCustomer")
//     public Map<String, Object> addCustomer(@RequestParam Map<String, String> params,
//                                            @AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             CustomerDTO dto = new CustomerDTO();
//             dto.setUserId(user.getUserId());
//             dto.setName(params.get("name"));
//             dto.setContact(params.get("contact"));
//             dto.setEmail(params.get("email"));
//             dto.setAddress(params.get("address"));
//             String id = params.get("id");
//             if (id != null && !id.isBlank())
//                 return ok(customerService.update(Long.valueOf(id), dto));
//             return ok(customerService.create(dto));
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/deleteCustomer")
//     public boolean deleteCustomer(@RequestParam(required = false) String checked) {
//         try {
//             parseIds(checked).forEach(customerService::delete);
//             return true;
//         } catch (Exception e) { return false; }
//     }

//     // ── Item ──────────────────────────────────────────────────────────────────

//     @GetMapping("/getUserItem")
//     public Map<String, Object> getUserItem(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(itemService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getAllItem")
//     public Map<String, Object> getAllItem(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(itemService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getUserItems")
//     public String getUserItems(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             StringBuilder sb = new StringBuilder();
//             itemService.getByUser(user.getUserId(), ALL).getContent()
//                 .forEach(i -> sb.append("<option value='").append(i.getId()).append("'>").append(i.getIname()).append("</option>"));
//             return sb.toString();
//         } catch (Exception e) { return "<option value=''>Item not available</option>"; }
//     }

//     @GetMapping("/getItem")
//     public Map<String, Object> getItem(@RequestParam Long itemId) {
//         try {
//             return ok(itemService.get(itemId));
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/addItem")
//     public Map<String, Object> addItem(@RequestParam Map<String, String> params,
//                                        @AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             ItemDTO dto = new ItemDTO();
//             dto.setUserId(user.getUserId());
//             dto.setIname(params.get("iname"));
//             dto.setIcode(params.get("icode"));
//             dto.setIdesc(params.get("idesc"));
//             String cid = params.get("companyId");
//             if (cid != null && !cid.isBlank()) dto.setCompanyId(Long.valueOf(cid));
//             String vid = params.get("venderId");
//             if (vid != null && !vid.isBlank()) dto.setVenderId(Long.valueOf(vid));
//             String id = params.get("id");
//             if (id != null && !id.isBlank())
//                 return ok(itemService.update(Long.valueOf(id), dto));
//             return ok(itemService.create(dto));
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/deleteItem")
//     public boolean deleteItem(@RequestParam(required = false) String checked) {
//         try {
//             parseIds(checked).forEach(itemService::delete);
//             return true;
//         } catch (Exception e) { return false; }
//     }

//     // ── Stock ─────────────────────────────────────────────────────────────────

//     @GetMapping("/getUserStock")
//     public Map<String, Object> getUserStock(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(stockService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getAllStock")
//     public Map<String, Object> getAllStock(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(stockService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getUserStocks")
//     public String getUserStocks(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             StringBuilder sb = new StringBuilder();
//             stockService.getByUser(user.getUserId(), ALL).getContent()
//                 .forEach(s -> sb.append("<option value='").append(s.getStockId()).append("'>Stock #").append(s.getStockId()).append("</option>"));
//             return sb.toString();
//         } catch (Exception e) { return "<option value=''>Item not available</option>"; }
//     }

//     @GetMapping("/getStock")
//     public Map<String, Object> getStock(@RequestParam Long itemId) {
//         try {
//             return ok(stockService.get(itemId));
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getStockByBatch")
//     public Map<String, Object> getStockByBatch(@RequestParam String batchNo) {
//         return error();
//     }

//     @GetMapping("/getBatchesByItem")
//     public String getBatchesByItem(@RequestParam Long itemId) {
//         return "<option value=''>Unable to find item batch</option>";
//     }

//     @PostMapping("/addStock")
//     public Map<String, Object> addStock(@RequestParam Map<String, String> params,
//                                         @AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             StockDTO dto = new StockDTO();
//             String iid = params.get("itemId");
//             if (iid != null && !iid.isBlank()) dto.setItemId(Long.valueOf(iid));
//             String pr = params.get("bpurchaseRate");
//             if (pr != null && !pr.isBlank()) dto.setBpurchaseRate(new BigDecimal(pr));
//             String sr = params.get("bsellRate");
//             if (sr != null && !sr.isBlank()) dto.setBsellRate(new BigDecimal(sr));
//             String st = params.get("stock");
//             if (st != null && !st.isBlank()) dto.setStock(Float.valueOf(st));
//             String id = params.get("stockId");
//             if (id != null && !id.isBlank())
//                 return ok(stockService.update(Long.valueOf(id), dto));
//             return ok(stockService.create(dto));
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/deleteStock")
//     public boolean deleteStock(@RequestParam(required = false) String checked) {
//         try {
//             parseIds(checked).forEach(stockService::delete);
//             return true;
//         } catch (Exception e) { return false; }
//     }

//     // ── Purchase ──────────────────────────────────────────────────────────────

//     @GetMapping("/getUserPurchase")
//     public Map<String, Object> getUserPurchase(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(purchaseService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getAllPurchase")
//     public Map<String, Object> getAllPurchase(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(purchaseService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/addPurchase")
//     public Map<String, Object> addPurchase(@RequestParam Map<String, String> params,
//                                            @AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             PurchaseDTO dto = new PurchaseDTO();
//             dto.setUserId(user.getUserId());
//             String sid = params.get("stockId");
//             if (sid != null && !sid.isBlank()) dto.setStockId(Long.valueOf(sid));
//             String qty = params.get("quantity");
//             if (qty != null && !qty.isBlank()) dto.setQuantity(Float.valueOf(qty));
//             String ta = params.get("totalAmount");
//             if (ta != null && !ta.isBlank()) dto.setTotalAmount(new BigDecimal(ta));
//             String na = params.get("netAmount");
//             if (na != null && !na.isBlank()) dto.setNetAmount(new BigDecimal(na));
//             dto.setPurchaseInvoiceNo(params.get("purchaseInvoiceNo"));
//             String id = params.get("purchaseId");
//             if (id != null && !id.isBlank())
//                 return ok(purchaseService.update(Long.valueOf(id), dto));
//             return ok(purchaseService.create(dto));
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/deletePurchase")
//     public boolean deletePurchase(@RequestParam(required = false) String checked) {
//         try {
//             parseIds(checked).forEach(purchaseService::delete);
//             return true;
//         } catch (Exception e) { return false; }
//     }

//     // ── Sell ──────────────────────────────────────────────────────────────────

//     @GetMapping("/getUserSell")
//     public Map<String, Object> getUserSell(@AuthenticationPrincipal AuthenticatedUser user,
//                                            @RequestParam(required = false) String q) {
//         try {
//             return ok(sellService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @GetMapping("/getAllSell")
//     public Map<String, Object> getAllSell(@AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(sellService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/loadSR")
//     public Map<String, Object> loadSR(@RequestParam Map<String, String> params,
//                                       @AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             return ok(sellService.getByUser(user.getUserId(), ALL).getContent());
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/addSell")
//     public Map<String, Object> addSell(@RequestBody com.myplus.business_service.dto.BusinessDTOs.SellDTO dto,
//                                        @AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             dto.setUserId(user.getUserId());
//             return ok(sellService.create(dto));
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/addSelling")
//     public Map<String, Object> addSelling(@RequestBody List<SellDTO> dtos,
//                                           @AuthenticationPrincipal AuthenticatedUser user) {
//         try {
//             dtos.forEach(d -> d.setUserId(user.getUserId()));
//             return ok(sellService.createBulk(dtos));
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/revertSell")
//     public Map<String, Object> revertSell(@RequestParam Map<String, String> params) {
//         try {
//             String id = params.get("sellId");
//             if (id == null || id.isBlank()) return error();
//             return ok(sellService.returnSale(Long.valueOf(id)));
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/saleReturn")
//     public Map<String, Object> saleReturn(@RequestParam Map<String, String> params) {
//         try {
//             String id = params.get("sellId");
//             if (id == null || id.isBlank()) return error();
//             return ok(sellService.returnSale(Long.valueOf(id)));
//         } catch (Exception e) { return error(); }
//     }

//     @PostMapping("/deleteSell")
//     public boolean deleteSell(@RequestParam(required = false) String checked) {
//         try {
//             parseIds(checked).forEach(sellService::delete);
//             return true;
//         } catch (Exception e) { return false; }
//     }

//     // ── ItemUnit (not in business-service — return empty) ─────────────────────

//     @GetMapping("/getUserItemUnit")
//     public Map<String, Object> getUserItemUnit() {
//         return ok(List.of());
//     }

//     @GetMapping("/getAllItemUnit")
//     public Map<String, Object> getAllItemUnit() {
//         return ok(List.of());
//     }

//     @GetMapping("/getUserItemUnits")
//     public String getUserItemUnits() {
//         return "<option value=''>Item not available</option>";
//     }

//     @PostMapping("/addItemUnit")
//     public Map<String, Object> addItemUnit() {
//         return error();
//     }

//     @PostMapping("/deleteItemUnit")
//     public boolean deleteItemUnit() {
//         return false;
//     }
// }
