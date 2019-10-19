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

import com.persistence.Repo.business.CompanyRepo;
import com.persistence.model.Company;
import com.service.IUserService;

@Service
@Transactional
public class CompanyService implements ICompanyService {

    @Autowired
    IUserService userService;
    
    @Autowired
    CompanyRepo companyRepo;

	@Override
	public List<Company> findAll() {
		// TODO Auto-generated method stub
		return companyRepo.findAll();
	}

	@Override
	public List<Company> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return companyRepo.findAll(sort);
	}

	@Override
	public List<Company> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return companyRepo.findAllById(ids);
	}

	@Override
	public <S extends Company> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return companyRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		companyRepo.flush();
	}

	@Override
	public <S extends Company> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return companyRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Company> entities) {
		// TODO Auto-generated method stub
		companyRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		companyRepo.deleteAllInBatch();
	}

	@Override
	public Company getOne(Long id) {
		// TODO Auto-generated method stub
		return companyRepo.getOne(id);
	}

	@Override
	public <S extends Company> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return companyRepo.findAll(example);
	}

	@Override
	public <S extends Company> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return companyRepo.findAll(example,sort);
	}

	@Override
	public Page<Company> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return companyRepo.findAll(pageable);
	}

	@Override
	public <S extends Company> S save(S entity) {
		// TODO Auto-generated method stub
		return companyRepo.save(entity);
	}

	@Override
	public Optional<Company> findById(Long id) {
		// TODO Auto-generated method stub
		return companyRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return companyRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return companyRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		companyRepo.deleteById(id);
		
	}

	@Override
	public void delete(Company entity) {
		// TODO Auto-generated method stub
		companyRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Company> entities) {
		// TODO Auto-generated method stub
		companyRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		companyRepo.deleteAll();
	}

	@Override
	public <S extends Company> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return companyRepo.findOne(example);
	}

	@Override
	public <S extends Company> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return companyRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Company> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return companyRepo.count(example);
	}

	@Override
	public <S extends Company> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return companyRepo.exists(example);
	}

}