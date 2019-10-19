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

import com.persistence.Repo.business.CustomerRepo;
import com.persistence.model.business.Customer;
import com.service.IUserService;

@Service
@Transactional
public class CustomerService implements ICustomerService{

    @Autowired
    IUserService userService;
    
    @Autowired
    CustomerRepo customerRepo;

	@Override
	public List<Customer> findAll() {
		// TODO Auto-generated method stub
		return customerRepo.findAll();
	}

	@Override
	public List<Customer> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return customerRepo.findAll(sort);
	}

	@Override
	public List<Customer> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return customerRepo.findAllById(ids);
	}

	@Override
	public <S extends Customer> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return customerRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		customerRepo.flush();
	}

	@Override
	public <S extends Customer> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return customerRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Customer> entities) {
		// TODO Auto-generated method stub
		customerRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		customerRepo.deleteAllInBatch();
	}

	@Override
	public Customer getOne(Long id) {
		// TODO Auto-generated method stub
		return customerRepo.getOne(id);
	}

	@Override
	public <S extends Customer> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return customerRepo.findAll(example);
	}

	@Override
	public <S extends Customer> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return customerRepo.findAll(example,sort);
	}

	@Override
	public Page<Customer> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return customerRepo.findAll(pageable);
	}

	@Override
	public <S extends Customer> S save(S entity) {
		// TODO Auto-generated method stub
		return customerRepo.save(entity);
	}

	@Override
	public Optional<Customer> findById(Long id) {
		// TODO Auto-generated method stub
		return customerRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return customerRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return customerRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		customerRepo.deleteById(id);
		
	}

	@Override
	public void delete(Customer entity) {
		// TODO Auto-generated method stub
		customerRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Customer> entities) {
		// TODO Auto-generated method stub
		customerRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		customerRepo.deleteAll();
	}

	@Override
	public <S extends Customer> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return customerRepo.findOne(example);
	}

	@Override
	public <S extends Customer> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return customerRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Customer> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return customerRepo.count(example);
	}

	@Override
	public <S extends Customer> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return customerRepo.exists(example);
	}

}