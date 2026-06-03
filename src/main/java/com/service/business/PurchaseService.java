package com.service.business;

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

import com.persistence.Repo.business.PurchaseRepo;
import com.persistence.model.User;
import com.persistence.model.business.Purchase;
import com.persistence.model.business.Stock;
import com.service.IUserService;
import com.web.dto.business.PurchaseDTO;
import com.web.util.AppUtil;
import com.web.util.RequestUtil;

@Service
@Transactional
public class PurchaseService implements IPurchaseService{

    @Autowired
    IUserService userService;
    
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
    
	@Override
	public List<Purchase> findAll() {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll();
	}

	@Override
	public List<Purchase> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(sort);
	}

	@Override
	public List<Purchase> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAllById(ids);
	}

	@Override
	public <S extends Purchase> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return purchaseRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		purchaseRepo.flush();
	}

	@Override
	public <S extends Purchase> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return purchaseRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Purchase> entities) {
		// TODO Auto-generated method stub
		purchaseRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		purchaseRepo.deleteAllInBatch();
	}

	@Override
	public Purchase getOne(Long id) {
		// TODO Auto-generated method stub
		return purchaseRepo.getOne(id);
	}

	@Override
	public <S extends Purchase> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(example);
	}

	@Override
	public <S extends Purchase> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(example,sort);
	}

	@Override
	public Page<Purchase> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(pageable);
	}

	@Override
	public <S extends Purchase> S save(S entity) {
		// TODO Auto-generated method stub
		return purchaseRepo.save(entity);
	}

	@Override
	public Optional<Purchase> findById(Long id) {
		// TODO Auto-generated method stub
		return purchaseRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return purchaseRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return purchaseRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		purchaseRepo.deleteById(id);
		
	}

	@Override
	public void delete(Purchase entity) {
		// TODO Auto-generated method stub
		purchaseRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Purchase> entities) {
		// TODO Auto-generated method stub
		purchaseRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		purchaseRepo.deleteAll();
	}

	@Override
	public <S extends Purchase> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return purchaseRepo.findOne(example);
	}

	@Override
	public <S extends Purchase> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Purchase> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return purchaseRepo.count(example);
	}

	@Override
	public <S extends Purchase> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return purchaseRepo.exists(example);
	}

	@Override
	@Transactional
	public Purchase addPurchase(PurchaseDTO dto) throws Exception {
		User user = requestUtil.getCurrentUser();
		dto.setUserId(user.getId());
		dto.setUserType(user.getUserType());

		Stock stock  = stockService.updateStock(dto);

		modelMapper.addConverter(appUtil.stringToLocalDateTimeIgnoreEmptyOrNull);
		modelMapper.addConverter(appUtil.stringToLocalDateIgnoreEmptyOrNull);
		Purchase obj = modelMapper.map(dto, Purchase.class);
		obj.setDated(appUtil.getDateTime(null));
		obj.setDated(LocalDateTime.now());
		obj.setStock(stock);
		return this.save(obj);
	}

	@Override
	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		purchaseRepo.deleteAllByIdInBatch(ids);
	}

	@Override
	public void deleteAllInBatch(Iterable<Purchase> entities) {
		purchaseRepo.deleteAllInBatch(entities);
	}

	@Override
	public Purchase getById(Long id) {
		return purchaseRepo.getById(id);
	}

	@Override
	public Purchase getReferenceById(Long id) {
		return purchaseRepo.getReferenceById(id);
	}

	@Override
	public <S extends Purchase> List<S> saveAllAndFlush(Iterable<S> entities) {
		return purchaseRepo.saveAllAndFlush(entities);
	}

	@Override
	public void deleteAllById(Iterable<? extends Long> ids) {
		purchaseRepo.deleteAllById(ids);
	}

	@Override
	public <S extends Purchase, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		return purchaseRepo.findBy(example, queryFunction);
	}

}