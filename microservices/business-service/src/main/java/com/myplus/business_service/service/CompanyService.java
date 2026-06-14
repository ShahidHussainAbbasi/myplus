package com.myplus.business_service.service;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import jakarta.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.stereotype.Service;

import com.myplus.business_service.repository.CompanyRepo;
import com.myplus.business_service.entity.Company;


@Service
@Transactional	
public class CompanyService implements ICompanyService {

    @Autowired
    CompanyRepo companyRepo;

	public List<Company> findAll() {
		// TODO Auto-generated method stub
		return companyRepo.findAll();
	}

	@Override
	public List<Company> findScoped(Long orgId, Long userId) {
		return companyRepo.findScoped(orgId, userId);
	}

	public List<Company> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return companyRepo.findAll(sort);
	}

	public List<Company> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return companyRepo.findAllById(ids);
	}

	public <S extends Company> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return companyRepo.saveAll(entities);
	}

	public void flush() {
		// TODO Auto-generated method stub
		companyRepo.flush();
	}

	public <S extends Company> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return companyRepo.saveAndFlush(entity);
	}

	public void deleteInBatch(Iterable<Company> entities) {
		// TODO Auto-generated method stub
		companyRepo.deleteInBatch(entities);
	}

	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		companyRepo.deleteAllInBatch();
	}

	public Company getOne(Long id) {
		// TODO Auto-generated method stub
		return companyRepo.getOne(id);
	}

	public <S extends Company> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return companyRepo.findAll(example);
	}

	public <S extends Company> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return companyRepo.findAll(example,sort);
	}

	public Page<Company> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return companyRepo.findAll(pageable);
	}

	public <S extends Company> S save(S entity) {
		// TODO Auto-generated method stub
		return companyRepo.save(entity);
	}

	public Optional<Company> findById(Long id) {
		// TODO Auto-generated method stub
		return companyRepo.findById(id);
	}

	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return companyRepo.existsById(id);
	}

	public long count() {
		// TODO Auto-generated method stub
		return companyRepo.count();
	}

	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		companyRepo.deleteById(id);
		
	}

	public void delete(Company entity) {
		// TODO Auto-generated method stub
		companyRepo.delete(entity);
		
	}

	public void deleteAll(Iterable<? extends Company> entities) {
		// TODO Auto-generated method stub
		companyRepo.deleteAll(entities);
	}

	public void deleteAll() {
		// TODO Auto-generated method stub
		companyRepo.deleteAll();
	}

	public <S extends Company> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return companyRepo.findOne(example);
	}

	public <S extends Company> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return companyRepo.findAll(example, pageable);
	}

	public <S extends Company> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return companyRepo.count(example);
	}

	public <S extends Company> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return companyRepo.exists(example);
	}

	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllByIdInBatch'");
	}

	public void deleteAllInBatch(Iterable<Company> entities) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllInBatch'");
	}

	public Company getById(Long id) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'getById'");
	}

	public Company getReferenceById(Long id) {
		// TODO Auto-generated method stub
		return companyRepo.getReferenceById(id);
		// throw new UnsupportedOperationException("Unimplemented method 'getReferenceById'");
	}

	public <S extends Company> List<S> saveAllAndFlush(Iterable<S> entities) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'saveAllAndFlush'");
	}

	public void deleteAllById(Iterable<? extends Long> ids) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllById'");
	}

	public <S extends Company, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'findBy'");
	}

}