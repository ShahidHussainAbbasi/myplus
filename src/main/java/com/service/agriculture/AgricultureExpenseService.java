package com.service.agriculture;

import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.agriculture.AgricultureExpenseRepository;
import com.persistence.model.agriculture.AgricultureExpense;

@Service
@Transactional
public class AgricultureExpenseService implements IAgricultureExpenseService {

	@Autowired
	AgricultureExpenseRepository AgricultureExpenseRep;
	
	@Override
	public List<AgricultureExpense> findAll() {
		return AgricultureExpenseRep.findAll();
	}

	@Override
	public List<AgricultureExpense> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return AgricultureExpenseRep.findAll(sort);
	}

	@Override
	public List<AgricultureExpense> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return AgricultureExpenseRep.findAllById(ids);
	}

	@Override
	public <S extends AgricultureExpense> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return AgricultureExpenseRep.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		AgricultureExpenseRep.flush();
	}

	@Override
	public <S extends AgricultureExpense> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void deleteInBatch(Iterable<AgricultureExpense> entities) {
		// TODO Auto-generated method stub
		AgricultureExpenseRep.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		AgricultureExpenseRep.deleteAllInBatch();
	}

	@Override
	public AgricultureExpense getOne(Long id) {
		// TODO Auto-generated method stub
		return AgricultureExpenseRep.getOne(id);
	}

	@Override
	public <S extends AgricultureExpense> List<S> findAll(Example<S> example) {
		return AgricultureExpenseRep.findAll(example);
	}

	@Override
	public <S extends AgricultureExpense> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return AgricultureExpenseRep.findAll(example, sort);
	}

	@Override
	public Page<AgricultureExpense> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return AgricultureExpenseRep.findAll(pageable);
	}

	@Override
	public <S extends AgricultureExpense> S save(S entity) {
		return AgricultureExpenseRep.save(entity);
	}

	@Override
	public Optional<AgricultureExpense> findById(Long id) {
		// TODO Auto-generated method stub
		return AgricultureExpenseRep.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return AgricultureExpenseRep.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return AgricultureExpenseRep.count();
	}

	@Override
	public void deleteById(Long id) {
		AgricultureExpenseRep.deleteById(id);
	}

	@Override
	public void delete(AgricultureExpense entity) {
		// TODO Auto-generated method stub
		AgricultureExpenseRep.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends AgricultureExpense> entities) {
		// TODO Auto-generated method stub
		AgricultureExpenseRep.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		AgricultureExpenseRep.deleteAll();
	}

	@Override
	public <S extends AgricultureExpense> Optional<S> findOne(Example<S> example) {
		return AgricultureExpenseRep.findOne(example);
	}

	@Override
	public <S extends AgricultureExpense> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return AgricultureExpenseRep.findAll(example, pageable);
	}

	@Override
	public <S extends AgricultureExpense> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return AgricultureExpenseRep.count(example);
	}

	@Override
	public <S extends AgricultureExpense> boolean exists(Example<S> example) {
		return AgricultureExpenseRep.exists(example);
	}



}