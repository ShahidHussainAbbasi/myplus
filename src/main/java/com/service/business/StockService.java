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

import com.persistence.Repo.business.StockRepo;
import com.persistence.model.business.Stock;

@Service
@Transactional
public class StockService implements IStockService {

    @Autowired
    StockRepo stockRepo;

	@Override
	public List<Stock> findAll() {
		// TODO Auto-generated method stub
		return stockRepo.findAll();
	}

	@Override
	public List<Stock> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return stockRepo.findAll(sort);
	}

	@Override
	public List<Stock> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return stockRepo.findAllById(ids);
	}

	@Override
	public <S extends Stock> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return stockRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		stockRepo.flush();
	}

	@Override
	public <S extends Stock> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return stockRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Stock> entities) {
		// TODO Auto-generated method stub
		stockRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		stockRepo.deleteAllInBatch();
	}

	@Override
	public Stock getOne(Long id) {
		// TODO Auto-generated method stub
		return stockRepo.getOne(id);
	}

	@Override
	public <S extends Stock> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return stockRepo.findAll(example);
	}

	@Override
	public <S extends Stock> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return stockRepo.findAll(example,sort);
	}

	@Override
	public Page<Stock> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return stockRepo.findAll(pageable);
	}

	@Override
	public <S extends Stock> S save(S entity) {
		// TODO Auto-generated method stub
		return stockRepo.save(entity);
	}

	@Override
	public Optional<Stock> findById(Long id) {
		// TODO Auto-generated method stub
		return stockRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return stockRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return stockRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		stockRepo.deleteById(id);
		
	}

	@Override
	public void delete(Stock entity) {
		// TODO Auto-generated method stub
		stockRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Stock> entities) {
		// TODO Auto-generated method stub
		stockRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		stockRepo.deleteAll();
	}

	@Override
	public <S extends Stock> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return stockRepo.findOne(example);
	}

	@Override
	public <S extends Stock> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return stockRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Stock> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return stockRepo.count(example);
	}

	@Override
	public <S extends Stock> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return stockRepo.exists(example);
	}

}