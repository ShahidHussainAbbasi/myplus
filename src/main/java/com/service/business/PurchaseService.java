package com.service.business;

import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.business.PurchaseRepo;
import com.persistence.model.business.Purchase;
import com.service.UserService;

@Service
@Transactional
public class PurchaseService implements IPurchaseService{

    @Autowired
    UserService userService;
    
    @Autowired
    PurchaseRepo purchaseRepo;

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

}