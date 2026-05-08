package com.service.business;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.stereotype.Service;

import com.persistence.Repo.business.CustomerHistoryRepo;
import com.persistence.Repo.business.ItemRepo;
import com.persistence.model.business.CustomerHistory;
import com.service.IUserService;
import com.web.util.RequestUtil;

@Service
@Transactional
public class CustomerHistoryService implements ICustomerHistoryService {

    @Autowired
    IUserService userService;
    
    @Autowired
    ItemRepo itemRepo;

	@Autowired
	RequestUtil requestUtil;
    
	@Autowired
	CustomerHistoryRepo CustomerHistoryRepo;

	@Override
	public List<CustomerHistory> findAll() {
		return CustomerHistoryRepo.findAll();
	}

	@Override
	public List<CustomerHistory> findAll(Sort sort) {
		return CustomerHistoryRepo.findAll(sort);
	}

	@Override
	public List<CustomerHistory> findAllById(Iterable<Long> ids) {
		return CustomerHistoryRepo.findAllById(ids);
	}

	@Override
	public <S extends CustomerHistory> List<S> saveAll(Iterable<S> entities) {
		return CustomerHistoryRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		CustomerHistoryRepo.flush();
	}

	@Override
	public <S extends CustomerHistory> S saveAndFlush(S entity) {
		return CustomerHistoryRepo.saveAndFlush(entity);
	}

	@Override
	public <S extends CustomerHistory> List<S> saveAllAndFlush(Iterable<S> entities) {
		return CustomerHistoryRepo.saveAllAndFlush(entities);
	}

	@Override
	public void deleteAllInBatch(Iterable<CustomerHistory> entities) {
		CustomerHistoryRepo.deleteAllInBatch(entities);
	}

	@Override
	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		CustomerHistoryRepo.deleteAllByIdInBatch(ids);
	}

	@Override
	public void deleteAllInBatch() {
		CustomerHistoryRepo.deleteAllInBatch();
	}


	@Override
	public CustomerHistory getReferenceById(Long id) {
		return CustomerHistoryRepo.getReferenceById(id);
	}

	@Override
	public <S extends CustomerHistory> List<S> findAll(Example<S> example) {
		return CustomerHistoryRepo.findAll(example);
	}

	@Override
	public <S extends CustomerHistory> List<S> findAll(Example<S> example, Sort sort) {
		return CustomerHistoryRepo.findAll(example, sort);
	}

	@Override
	public Page<CustomerHistory> findAll(Pageable pageable) {
		return CustomerHistoryRepo.findAll(pageable);
	}

	@Override
	public <S extends CustomerHistory> S save(S entity) {
		return CustomerHistoryRepo.save(entity);
	}

	@Override
	public Optional<CustomerHistory> findById(Long id) {
		return CustomerHistoryRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		return CustomerHistoryRepo.existsById(id);
	}

	@Override
	public long count() {
		return CustomerHistoryRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		CustomerHistoryRepo.deleteById(id);
	}

	@Override
	public void delete(CustomerHistory entity) {
		CustomerHistoryRepo.delete(entity);
	}

	@Override
	public void deleteAllById(Iterable<? extends Long> ids) {
		CustomerHistoryRepo.deleteAllById(ids);
	}

	@Override
	public void deleteAll(Iterable<? extends CustomerHistory> entities) {
		CustomerHistoryRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		CustomerHistoryRepo.deleteAll();
	}

	@Override
	public <S extends CustomerHistory> Optional<S> findOne(Example<S> example) {
		return CustomerHistoryRepo.findOne(example);
	}

	@Override
	public <S extends CustomerHistory> Page<S> findAll(Example<S> example, Pageable pageable) {
		return CustomerHistoryRepo.findAll(example, pageable);
	}

	@Override
	public <S extends CustomerHistory> long count(Example<S> example) {
		return CustomerHistoryRepo.count(example);
	}

	@Override
	public <S extends CustomerHistory> boolean exists(Example<S> example) {
		return CustomerHistoryRepo.exists(example);
	}

	@Override
	public <S extends CustomerHistory, R> R findBy(Example<S> example,
			Function<FetchableFluentQuery<S>, R> queryFunction) {
		return CustomerHistoryRepo.findBy(example, queryFunction);
	}

	@Override
	public CustomerHistory getOne(Long id) {
		return CustomerHistoryRepo.getOne(id);
	}

	@Override
	public CustomerHistory getById(Long id) {
		return CustomerHistoryRepo.getById(id);
	}
	
	}