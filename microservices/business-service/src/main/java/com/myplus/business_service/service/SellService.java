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
import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.Sell;
import com.myplus.business_service.entity.Stock;

import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.RequestUtil;

@Service
@Transactional
public class SellService implements ISellService {

	ModelMapper modelMapper = new ModelMapper();
	
	@Autowired
	IStockService stockService;

	@Autowired
	SellRepo sellRepo;

	@Autowired
	com.myplus.business_service.repository.CustomerHistoryRepo customerHistoryRepo;

	@Autowired
	RequestUtil requestUtil;

	@Autowired
	IItemService itemService;

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

	public List<Sell> findSellByStartDate(LocalDateTime sd, Long orgId, Long userId) {
		return sellRepo.findSellByStartDate(sd, orgId, userId);
	}

	public List<Sell> findSellByEndDate(LocalDateTime ed, Long orgId, Long userId) {
		return sellRepo.findSellByEndDate(ed, orgId, userId);
	}

	public List<Sell> findSellByDates(LocalDateTime sd, LocalDateTime ed, Long orgId, Long userId) {
		return sellRepo.findSellByDates(sd, ed, orgId, userId);
	}

	@SuppressWarnings("null")
	@Override
	@Transactional
	public void addSell(List<Sell> dtos) throws Exception {
		// List<Sell> objs = new ArrayList<>();
		if(!appUtil.isEmptyOrNull(dtos)) {
			dtos.forEach(dto ->{
				if(dto.getStock() == null) return;
				Stock stock = stockService.updateStock(dto);
				if(!appUtil.isEmptyOrNull(stock)) {
					modelMapper.addConverter(appUtil.stringToLocalDateTime);
					modelMapper.addConverter(appUtil.stringToLocalDate);
					Sell obj = modelMapper.map(dto, Sell.class);
					if(obj.getSellId() != null && obj.getSellId() == 0) obj.setSellId(null);
					// Persist the invoice link (FK only). Previously this was nulled to avoid the
					// cascade-MERGE overwriting the invoice row with the detached mapped entity's partial
					// data — but that lost the link entirely, leaving sells unattributable to an invoice
					// (breaking grouping + edit). A managed reference by id sets the FK without an
					// unwanted overwrite (merging an unchanged proxy is a no-op).
					Long chId = (dto.getCustomerHistory() != null)
							? dto.getCustomerHistory().getCustomer_history_id() : null;
					obj.setCustomerHistory(chId != null ? customerHistoryRepo.getReferenceById(chId) : null);
					obj.setSellRate(stock.getBsellRate());
					obj.setDiscount(stock.getBsellDiscount());
					obj.setDt(stock.getBsellDiscountType());
					obj.setSrp(dto.getSrp());
					obj.setUpdated(dto.getUpdated());
					obj.setTotalAmount(dto.getTotalAmount());
					obj.setNetAmount(dto.getNetAmount());
					obj.setStock(stock);
					AuthenticatedUser actor = requestUtil.getCurrentUser();
					obj.setUserId(actor.getUserId());                       // audit: who sold
					obj.setOrganizationId(actor.getOrganizationId());       // tenant scope
					obj.setDated(LocalDateTime.now());
					obj.setUpdated(LocalDateTime.now());
					stockService.save(stock);
					this.save(obj);
					// objs.add(this.save(obj));
				}
			});
		}
		// return objs;
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