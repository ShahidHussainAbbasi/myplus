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

import com.persistence.Repo.agriculture.AgricultureIncomeRepository;
import com.persistence.model.agriculture.AgricultureIncome;

@Service
@Transactional
public class AgricultureIncomeService implements IAgricultureIncomeService {

	@Autowired
	AgricultureIncomeRepository AgricultureIncomeRepo;
	@Override
	public List<AgricultureIncome> findAll() {
		return AgricultureIncomeRepo.findAll();
	}

	@Override
	public List<AgricultureIncome> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<AgricultureIncome> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return AgricultureIncomeRepo.findAllById(ids);
	}

	@Override
	public <S extends AgricultureIncome> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return AgricultureIncomeRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		AgricultureIncomeRepo.flush();
	}

	@Override
	public <S extends AgricultureIncome> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void deleteInBatch(Iterable<AgricultureIncome> entities) {
		// TODO Auto-generated method stub
		AgricultureIncomeRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		AgricultureIncomeRepo.deleteAllInBatch();
	}

	@Override
	public AgricultureIncome getOne(Long id) {
		// TODO Auto-generated method stub
		return AgricultureIncomeRepo.getOne(id);
	}

	@Override
	public <S extends AgricultureIncome> List<S> findAll(Example<S> example) {
		return AgricultureIncomeRepo.findAll(example);
	}

	@Override
	public <S extends AgricultureIncome> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Page<AgricultureIncome> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends AgricultureIncome> S save(S entity) {
		return AgricultureIncomeRepo.save(entity);
	}

	@Override
	public Optional<AgricultureIncome> findById(Long id) {
		return AgricultureIncomeRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return AgricultureIncomeRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return AgricultureIncomeRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		AgricultureIncomeRepo.deleteById(id);	
	}

	@Override
	public void delete(AgricultureIncome entity) {
		AgricultureIncomeRepo.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends AgricultureIncome> entities) {
		// TODO Auto-generated method stub
		AgricultureIncomeRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public <S extends AgricultureIncome> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return AgricultureIncomeRepo.findOne(example);
	}

	@Override
	public <S extends AgricultureIncome> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends AgricultureIncome> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public <S extends AgricultureIncome> boolean exists(Example<S> example) {
		return AgricultureIncomeRepo.exists(example);
	}


}