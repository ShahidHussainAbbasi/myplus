package com.myplus.business_service.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import jakarta.transaction.Transactional;

import org.apache.poi.xwpf.usermodel.*;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.stereotype.Service;

import com.myplus.business_service.repository.SellRepo;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Sell;

import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.RequestUtil;

@Service
@Transactional
public class SellService implements ISellService {

	ModelMapper modelMapper = new ModelMapper();

	@Autowired
	SellRepo sellRepo;

	@Autowired
	com.myplus.business_service.repository.CustomerHistoryRepo customerHistoryRepo;

	@Autowired
	RequestUtil requestUtil;

	/** UI/UX P3: names + prices for the quick-pick tiles, resolved in ONE batched call (see topProducts). */
	@Autowired
	private com.myplus.commerce.contracts.client.CatalogClient catalogClient;

	@Autowired
	private AppUtil appUtil;

	private XWPFDocument document;


	public List<Sell> findAll() {
		// TODO Auto-generated method stub
		return sellRepo.findAll();
	}

	public List<Sell> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(sort);
	}

	public List<Sell> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return sellRepo.findAllById(ids);
	}

	public <S extends Sell> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return sellRepo.saveAll(entities);
	}

	public void flush() {
		// TODO Auto-generated method stub
		sellRepo.flush();
	}

	public <S extends Sell> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return sellRepo.saveAndFlush(entity);
	}

	public void deleteInBatch(Iterable<Sell> entities) {
		// Delegate batch delete to repository
		sellRepo.deleteAllInBatch(entities);
	}

	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		sellRepo.deleteAllInBatch();
	}

	public Sell getOne(Long id) {
		// TODO Auto-generated method stub
		return sellRepo.getOne(id);
	}

	public <S extends Sell> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(example);
	}

	public <S extends Sell> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(example, sort);
	}

	public Page<Sell> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(pageable);
	}

	public <S extends Sell> S save(S entity) {
		// TODO Auto-generated method stub
		return sellRepo.save(entity);
	}

	public Optional<Sell> findById(Long id) {
		// TODO Auto-generated method stub
		return sellRepo.findById(id);
	}

	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return sellRepo.existsById(id);
	}

	public long count() {
		// TODO Auto-generated method stub
		return sellRepo.count();
	}

	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		sellRepo.deleteById(id);

	}

	public void delete(Sell entity) {
		// TODO Auto-generated method stub
		sellRepo.delete(entity);

	}

	public void deleteAll(Iterable<? extends Sell> entities) {
		// TODO Auto-generated method stub
		sellRepo.deleteAll(entities);
	}

	public void deleteAll() {
		// TODO Auto-generated method stub
		sellRepo.deleteAll();
	}

	public <S extends Sell> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return sellRepo.findOne(example);
	}

	public <S extends Sell> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(example, pageable);
	}

	public <S extends Sell> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return sellRepo.count(example);
	}

	public <S extends Sell> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return sellRepo.exists(example);
	}

	@Override
	public List<Sell> findScoped(Long orgId, Long userId) {
		return sellRepo.findScoped(orgId, userId);
	}

	@Override
	public List<Sell> findScoped(Long orgId, Long userId, org.springframework.data.domain.Pageable pageable) {
		return sellRepo.findScoped(orgId, userId, pageable);
	}

	@Override
	public List<Sell> findByInvoiceScoped(Long chId, Long orgId, Long userId) {
		return sellRepo.findByInvoiceScoped(chId, orgId, userId);
	}

	@Override
	public List<Sell> findOwnScoped(Long orgId, Long userId) {
		return sellRepo.findOwnScoped(orgId, userId);
	}

	@Override
	public List<Sell> findScopedByStores(Long orgId, java.util.Collection<Long> storeIds) {
		return sellRepo.findScopedByStores(orgId, storeIds);
	}

	@Override
	public List<Sell> findOwnScopedByStores(Long orgId, Long userId, java.util.Collection<Long> storeIds) {
		return sellRepo.findOwnScopedByStores(orgId, userId, storeIds);
	}

	/**
	 * UI/UX P3 — the shop's best sellers by units, for the POS quick-pick tiles.
	 *
	 * <p>Names and prices come from ONE batched catalog call ({@code getProducts(ids)}), never a lookup
	 * per tile: this runs when the sale screen opens, and a per-row round trip on that path is exactly
	 * the cost the tiles exist to remove.
	 *
	 * <p>A catalog hiccup degrades to an EMPTY grid rather than an error. The tiles are an accelerator;
	 * every product remains reachable through the normal picker, so a shop must never be unable to sell
	 * because a convenience could not be drawn.
	 */
	@Override
	public java.util.List<java.util.Map<String, Object>> topProducts(int days, int limit) {
		int d = (days > 0) ? days : 30;
		int n = (limit > 0) ? Math.min(limit, 24) : 9;   // a grid nobody can scan at a glance is not a shortcut
		LocalDateTime since = LocalDateTime.now().minusDays(d);
		Long orgId = com.myplus.common.security.CurrentUser.organizationId();
		Long userId = com.myplus.common.security.CurrentUser.userId();
		java.util.Set<Long> stores = requestUtil.accessibleStoreIds();

		// JPQL `IN` cannot take an empty collection, so the no-grants case uses the org-wide query —
		// which is also the correct behaviour for a single-store tenant.
		List<Object[]> rows = (stores == null || stores.isEmpty())
			? sellRepo.topProductsScoped(since, orgId, userId, org.springframework.data.domain.PageRequest.of(0, n))
			: sellRepo.topProductsByStores(since, orgId, stores, org.springframework.data.domain.PageRequest.of(0, n));

		java.util.List<Long> ids = new ArrayList<>();
		java.util.Map<Long, Double> unitsById = new java.util.LinkedHashMap<>();
		for (Object[] r : rows) {
			if (r == null || r[0] == null) continue;
			Long pid = ((Number) r[0]).longValue();
			ids.add(pid);
			unitsById.put(pid, r[1] == null ? 0d : ((Number) r[1]).doubleValue());
		}
		if (ids.isEmpty()) return java.util.Collections.emptyList();

		java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> byId;
		try {
			byId = catalogClient.getProducts(ids).stream()
				.collect(java.util.stream.Collectors.toMap(
					com.myplus.commerce.contracts.dto.ProductRef::getId, p -> p, (a, b) -> a));
		} catch (Exception e) {
			return java.util.Collections.emptyList();
		}

		java.util.List<java.util.Map<String, Object>> out = new ArrayList<>();
		for (Long pid : ids) {
			com.myplus.commerce.contracts.dto.ProductRef p = byId.get(pid);
			// A product deleted or deactivated since it was last sold must not appear as a tile that
			// cannot be rung up. Dropping it keeps the grid honest; the order of the rest is unchanged.
			if (p == null) continue;
			java.util.Map<String, Object> tile = new java.util.LinkedHashMap<>();
			tile.put("productId", pid);
			tile.put("name", p.getName());
			tile.put("sku", p.getSku());
			tile.put("unit", p.getUnit());
			tile.put("sellingPrice", p.getSellingPrice());
			tile.put("units", unitsById.get(pid));
			out.add(tile);
		}
		return out;
	}

	public List<Sell> findSellByStartDate(LocalDateTime sd, Long orgId, Long userId) {
		return sellRepo.findSellByStartDate(sd, orgId, userId);
	}

	public List<Sell> findSellByEndDate(LocalDateTime ed, Long orgId, Long userId) {
		return sellRepo.findSellByEndDate(ed, orgId, userId);
	}

	public List<Sell> findSellByDates(LocalDateTime sd, LocalDateTime ed, Long orgId, Long userId) {
		return sellRepo.findSellByDates(sd, ed, orgId, userId);
	}

	@Override
	@Transactional
	public void addSell(List<Sell> dtos) throws Exception {
		// M3c.4d (slice 86): the legacy local-Stock sell-write path is RETIRED. All sales go through the inventory
		// reservation saga (SagaSellService.addSell → SagaSaleWriter), which writes sell.productId with no local Stock
		// row. This interface method is no longer wired to any endpoint; left as a guard so any stray caller fails loud.
		throw new UnsupportedOperationException(
				"Local-Stock sell path retired; record sales via SagaSellService.addSell (the inventory saga).");
	}
	
	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		sellRepo.deleteAllByIdInBatch(ids);
	}

	public void deleteAllInBatch(Iterable<Sell> entities) {
		sellRepo.deleteAllInBatch(entities);
	}

	public Sell getById(Long id) {
		return sellRepo.getById(id);
	}

	public Sell getReferenceById(Long id) {
		return sellRepo.getReferenceById(id);
	}

	public <S extends Sell> List<S> saveAllAndFlush(Iterable<S> entities) {
		return sellRepo.saveAllAndFlush(entities);
	}

	public void deleteAllById(Iterable<? extends Long> ids) {
		sellRepo.deleteAllById(ids);
	}

	public <S extends Sell, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		return sellRepo.findBy(example, queryFunction);
	}

}