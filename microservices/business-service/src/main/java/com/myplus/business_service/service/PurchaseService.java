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
import com.myplus.business_service.entity.Stock;

import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.RequestUtil;

@Service
@Transactional
public class PurchaseService implements IPurchaseService{

    @Autowired
    PurchaseRepo purchaseRepo;

    @Autowired
    IStockService stockService;
    
/*    @Autowired
    IBatchService batchService;
*/
    @Autowired
    RequestUtil requestUtil;
    
    @Autowired
    AppUtil appUtil;

    ModelMapper modelMapper = new ModelMapper();
    
	public List<Purchase> findAll() {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll();
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
		Stock stock = stockService.updateStock(dto);

		modelMapper.addConverter(appUtil.stringToLocalDateTimeIgnoreEmptyOrNull);
		modelMapper.addConverter(appUtil.stringToLocalDateIgnoreEmptyOrNull);
		Purchase obj = modelMapper.map(dto, Purchase.class);
		obj.setDated(appUtil.getDateTime(null));
		obj.setDated(LocalDateTime.now());
		obj.setStock(stock);
		return this.save(obj);
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