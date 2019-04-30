package com.service.business;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.business.SellRepo;
import com.persistence.model.business.Sell;
import com.service.UserService;

@Service
@Transactional
public class SellService implements ISellService {

    @Autowired
    UserService userService;
    
    @Autowired
    SellRepo sellRepo;

	@Override
	public List<Sell> findAll() {
		// TODO Auto-generated method stub
		return sellRepo.findAll();
	}

	@Override
	public List<Sell> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(sort);
	}

	@Override
	public List<Sell> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return sellRepo.findAllById(ids);
	}

	@Override
	public <S extends Sell> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return sellRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		sellRepo.flush();
	}

	@Override
	public <S extends Sell> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return sellRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Sell> entities) {
		// TODO Auto-generated method stub
		sellRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		sellRepo.deleteAllInBatch();
	}

	@Override
	public Sell getOne(Long id) {
		// TODO Auto-generated method stub
		return sellRepo.getOne(id);
	}

	@Override
	public <S extends Sell> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(example);
	}

	@Override
	public <S extends Sell> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(example,sort);
	}

	@Override
	public Page<Sell> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(pageable);
	}

	@Override
	public <S extends Sell> S save(S entity) {
		// TODO Auto-generated method stub
		return sellRepo.save(entity);
	}

	@Override
	public Optional<Sell> findById(Long id) {
		// TODO Auto-generated method stub
		return sellRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return sellRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return sellRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		sellRepo.deleteById(id);
		
	}

	@Override
	public void delete(Sell entity) {
		// TODO Auto-generated method stub
		sellRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Sell> entities) {
		// TODO Auto-generated method stub
		sellRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		sellRepo.deleteAll();
	}

	@Override
	public <S extends Sell> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return sellRepo.findOne(example);
	}

	@Override
	public <S extends Sell> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Sell> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return sellRepo.count(example);
	}

	@Override
	public <S extends Sell> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return sellRepo.exists(example);
	}

	@Override
	public List<Sell> findSellByStartDate(LocalDateTime sd, Long userId) {
		// TODO Auto-generated method stub
		return sellRepo.findSellByStartDate(sd, userId);
	}

	@Override
	public List<Sell> findSellByEndDate(LocalDateTime ed, Long userId) {
		// TODO Auto-generated method stub
		return sellRepo.findSellByEndDate(ed, userId);
	}

	@Override
	public List<Sell> findSellByDates(LocalDateTime sd, LocalDateTime ed, Long userId) {
		// TODO Auto-generated method stub
		return sellRepo.findSellByDates(sd, ed, userId);
	}

}