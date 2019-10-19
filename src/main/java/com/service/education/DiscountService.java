package com.service.education;

import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.education.DiscountRepo;
import com.persistence.model.education.Discount;

@Service
@Transactional
public class DiscountService implements IDiscountService {

    @Autowired
    DiscountRepo discountRepo;

	@Override
	public List<Discount> findAll() {
		// TODO Auto-generated method stub
		return discountRepo.findAll();
	}

	@Override
	public List<Discount> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return discountRepo.findAll(sort);
	}

	@Override
	public List<Discount> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return discountRepo.findAllById(ids);
	}

	@Override
	public <S extends Discount> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return discountRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		discountRepo.flush();
	}

	@Override
	public <S extends Discount> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return discountRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Discount> entities) {
		// TODO Auto-generated method stub
		discountRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		discountRepo.deleteAllInBatch();
	}

	@Override
	public Discount getOne(Long id) {
		// TODO Auto-generated method stub
		return discountRepo.getOne(id);
	}

	@Override
	public <S extends Discount> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return discountRepo.findAll(example);
	}

	@Override
	public <S extends Discount> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return discountRepo.findAll(example,sort);
	}

	@Override
	public Page<Discount> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return discountRepo.findAll(pageable);
	}

	@Override
	public <S extends Discount> S save(S entity) {
		// TODO Auto-generated method stub
		return discountRepo.save(entity);
	}

	@Override
	public Optional<Discount> findById(Long id) {
		// TODO Auto-generated method stub
		return discountRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return discountRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return discountRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		discountRepo.deleteById(id);
		
	}

	@Override
	public void delete(Discount entity) {
		// TODO Auto-generated method stub
		discountRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Discount> entities) {
		// TODO Auto-generated method stub
		discountRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		discountRepo.deleteAll();
	}

	@Override
	public <S extends Discount> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return discountRepo.findOne(example);
	}

	@Override
	public <S extends Discount> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return discountRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Discount> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return discountRepo.count(example);
	}

	@Override
	public <S extends Discount> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return discountRepo.exists(example);
	}

	@Override
	public int updateStatus(String status, String id) {
		// TODO Auto-generated method stub
		return discountRepo.updateStatus(status, id);
	}


    
}