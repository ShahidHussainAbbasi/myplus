package com.myplus.business_service.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import jakarta.transaction.Transactional;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.stereotype.Service;

import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Purchase;

import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.dto.StockDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.RequestUtil;

@Service
@Transactional
public class PurchaseService implements IPurchaseService{

    @Autowired
    PurchaseRepo purchaseRepo;

/*    @Autowired
    IBatchService batchService;
*/
    @Autowired
    RequestUtil requestUtil;

    @Autowired
    AppUtil appUtil;

    @Autowired
    com.myplus.business_service.config.TradeSagaProperties tradeSagaProperties;

    @Autowired
    com.myplus.business_service.repository.ItemCatalogMapRepo itemCatalogMapRepo;

    @Autowired
    com.myplus.commerce.contracts.client.InventoryClient inventoryClient;

    @Autowired
    CatalogMigrationService catalogMigrationService;   // M3.2: auto-map an item on purchase so it reaches inventory

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PurchaseService.class);

    ModelMapper modelMapper = new ModelMapper();
    
	public List<Purchase> findAll() {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll();
	}

	@Override
	public List<Purchase> findScoped(Long orgId, Long userId) {
		return purchaseRepo.findScoped(orgId, userId);
	}

	@Override
	public List<Purchase> findOwnScoped(Long orgId, Long userId) {
		return purchaseRepo.findOwnScoped(orgId, userId);
	}

	public List<Purchase> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(sort);
	}

	public List<Purchase> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAllById(ids);
	}

	public <S extends Purchase> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return purchaseRepo.saveAll(entities);
	}

	public void flush() {
		// TODO Auto-generated method stub
		purchaseRepo.flush();
	}

	public <S extends Purchase> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return purchaseRepo.saveAndFlush(entity);
	}

	public void deleteInBatch(Iterable<Purchase> entities) {
		// TODO Auto-generated method stub
		purchaseRepo.deleteInBatch(entities);
	}

	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		purchaseRepo.deleteAllInBatch();
	}

	public Purchase getOne(Long id) {
		// TODO Auto-generated method stub
		return purchaseRepo.getOne(id);
	}

	public <S extends Purchase> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(example);
	}

	public <S extends Purchase> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(example,sort);
	}

	public Page<Purchase> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(pageable);
	}

	public <S extends Purchase> S save(S entity) {
		// TODO Auto-generated method stub
		return purchaseRepo.save(entity);
	}

	public Optional<Purchase> findById(Long id) {
		// TODO Auto-generated method stub
		return purchaseRepo.findById(id);
	}

	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return purchaseRepo.existsById(id);
	}

	public long count() {
		// TODO Auto-generated method stub
		return purchaseRepo.count();
	}

	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		purchaseRepo.deleteById(id);
		
	}

	public void delete(Purchase entity) {
		// TODO Auto-generated method stub
		purchaseRepo.delete(entity);
		
	}

	public void deleteAll(Iterable<? extends Purchase> entities) {
		// TODO Auto-generated method stub
		purchaseRepo.deleteAll(entities);
	}

	public void deleteAll() {
		// TODO Auto-generated method stub
		purchaseRepo.deleteAll();
	}

	public <S extends Purchase> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return purchaseRepo.findOne(example);
	}

	public <S extends Purchase> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(example, pageable);
	}

	public <S extends Purchase> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return purchaseRepo.count(example);
	}

	public <S extends Purchase> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return purchaseRepo.exists(example);
	}

	@Override
	@Transactional
	public Purchase addPurchase(PurchaseDTO dto) throws Exception {
		AuthenticatedUser user = requestUtil.getCurrentUser();
		dto.setUserId(user.getUserId());

		modelMapper.addConverter(appUtil.stringToLocalDateTimeIgnoreEmptyOrNull);
		modelMapper.addConverter(appUtil.stringToLocalDateIgnoreEmptyOrNull);
		Purchase obj = modelMapper.map(dto, Purchase.class);
		obj.setUpdated(obj.getUpdated()!=null?obj.getUpdated():LocalDateTime.now());
		obj.setDated(LocalDateTime.now());
		obj.setUserId(user.getUserId());                  // audit
		obj.setOrganizationId(user.getOrganizationId());  // tenant scope

		// M3c.4b (slice 84): the purchase is self-describing — copy its batch/rate snapshot straight off the DTO
		// (StockDTO scalar types already match Purchase; bexpDate parsed via AppUtil) instead of going through a local
		// Stock entity. Inventory stays authoritative for on-hand (pushed below).
		obj.setItemId(dto.getItemId());
		obj.setProductId(catalogMigrationService.ensureMapped(dto.getItemId(), user.getOrganizationId(), user.getUserId()));
		StockDTO snap = dto.getStock();
		if (snap != null) {
			obj.setBatchNo(snap.getBatchNo());
			obj.setBpurchaseRate(snap.getBpurchaseRate());
			obj.setBsellRate(snap.getBsellRate());
			obj.setBpurchaseDiscount(snap.getBpurchaseDiscount());
			obj.setBsellDiscount(snap.getBsellDiscount());
			obj.setBpurchaseDiscountType(snap.getBpurchaseDiscountType());
			obj.setBsellDiscountType(snap.getBsellDiscountType());
			obj.setBexpDate(appUtil.toLocalDateOrNull(snap.getBexpDate()));
		}
		Purchase saved = this.save(obj);
		pushPurchaseToInventory(saved, dto, user);        // dual-write stock-in to inventory (authoritative)
		return saved;
	}

	/**
	 * D3 (slice 33) + M3.2 (slice 63): when the saga is enabled, push the purchased quantity into inventory so
	 * inventory is authoritative for stock. The item is auto-mapped to a catalog product on demand
	 * ({@code ensureMapped}) — so EVERY purchase reaches inventory, including legacy items never bulk-migrated.
	 * Best-effort: a failure (catalog/inventory down) never fails the purchase (recorded locally; reconcile later).
	 */
	void pushPurchaseToInventory(Purchase obj, PurchaseDTO dto, AuthenticatedUser user) {
		if (!tradeSagaProperties.isEnabled() || dto.getQuantity() == null || dto.getQuantity() <= 0) {
			return;
		}
		try {
			Long productId = obj.getProductId();          // M3b: mapped once in addPurchase
			if (productId == null) return;
			inventoryClient.importStock(List.of(
					com.myplus.commerce.contracts.dto.StockImportLine.builder()
							.productId(productId)
							.quantity(dto.getQuantity())
							.batchNo(obj.getBatchNo())
							.expiryDate(obj.getBexpDate())
							.purchasePrice(obj.getBpurchaseRate())
							.costPrice(obj.getBpurchaseRate())
							.build()));
		} catch (Exception ex) {
			LOG.warn("M3b: inventory stock-in failed for item {} (purchase recorded locally; reconcile later)",
					dto.getItemId(), ex);
		}
	}

	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		purchaseRepo.deleteAllByIdInBatch(ids);
	}

	public void deleteAllInBatch(Iterable<Purchase> entities) {
		purchaseRepo.deleteAllInBatch(entities);
	}

	public Purchase getById(Long id) {
		return purchaseRepo.getById(id);
	}

	public Purchase getReferenceById(Long id) {
		return purchaseRepo.getReferenceById(id);
	}

	public <S extends Purchase> List<S> saveAllAndFlush(Iterable<S> entities) {
		return purchaseRepo.saveAllAndFlush(entities);
	}

	public void deleteAllById(Iterable<? extends Long> ids) {
		purchaseRepo.deleteAllById(ids);
	}

	public <S extends Purchase, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		return purchaseRepo.findBy(example, queryFunction);
	}

}