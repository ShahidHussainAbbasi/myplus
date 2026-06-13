package com.myplus.business_service.service;

import java.time.LocalDateTime;
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

import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Customer;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.RequestUtil;

@Service
@Transactional
public class CustomerService implements ICustomerService{

    @Autowired
    CustomerRepo customerRepo;

    @Autowired
    private AppUtil appUtil;  

	@Autowired
	RequestUtil requestUtil;

	// @Autowired
	// ObjectMapperUtils objectMapperUtils;

	public List<Customer> findAll() {
return customerRepo.findAll();
	}

	public List<Customer> findAll(Sort sort) {
return customerRepo.findAll(sort);
	}

	public List<Customer> findAllById(Iterable<Long> ids) {
return customerRepo.findAllById(ids);
	}

	public <S extends Customer> List<S> saveAll(Iterable<S> entities) {
return customerRepo.saveAll(entities);
	}

	public void flush() {
customerRepo.flush();
	}

	public <S extends Customer> S saveAndFlush(S entity) {
return customerRepo.saveAndFlush(entity);
	}

	public void deleteInBatch(Iterable<Customer> entities) {
customerRepo.deleteInBatch(entities);
	}

	public void deleteAllInBatch() {
customerRepo.deleteAllInBatch();
	}

	public Customer getOne(Long id) {
return customerRepo.getOne(id);
	}

	public <S extends Customer> List<S> findAll(Example<S> example) {
return customerRepo.findAll(example);
	}

	public <S extends Customer> List<S> findAll(Example<S> example, Sort sort) {
return customerRepo.findAll(example,sort);
	}

	public Page<Customer> findAll(Pageable pageable) {
return customerRepo.findAll(pageable);
	}

	public <S extends Customer> S save(S entity) {
return customerRepo.save(entity);
	}

	public Optional<Customer> findById(Long id) {
return customerRepo.findById(id);
	}

	public boolean existsById(Long id) {
return customerRepo.existsById(id);
	}

	public long count() {
return customerRepo.count();
	}

	public void deleteById(Long id) {
customerRepo.deleteById(id);
		
	}

	public void delete(Customer entity) {
customerRepo.delete(entity);
		
	}

	public void deleteAll(Iterable<? extends Customer> entities) {
customerRepo.deleteAll(entities);
	}

	public void deleteAll() {
customerRepo.deleteAll();
	}

	public <S extends Customer> Optional<S> findOne(Example<S> example) {
return customerRepo.findOne(example);
	}

	public <S extends Customer> Page<S> findAll(Example<S> example, Pageable pageable) {
return customerRepo.findAll(example, pageable);
	}

	public <S extends Customer> long count(Example<S> example) {
return customerRepo.count(example);
	}

	public <S extends Customer> boolean exists(Example<S> example) {
return customerRepo.exists(example);
	}

	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		customerRepo.deleteAllByIdInBatch(ids);
	}

	public void deleteAllInBatch(Iterable<Customer> entities) {
				customerRepo.deleteAllInBatch(entities);

	}

	public Customer getById(Long id) {
		return customerRepo.getReferenceById(id);
	}

	public Customer getReferenceById(Long id) {
		return customerRepo.getReferenceById(id);
	}

	public <S extends Customer> List<S> saveAllAndFlush(Iterable<S> entities) {
		return customerRepo.saveAllAndFlush(entities);
	}

	public void deleteAllById(Iterable<? extends Long> ids) {
		customerRepo.deleteAllById(ids);
	}

	public <S extends Customer, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		return customerRepo.findBy(example, queryFunction);
	}

	public List<Customer> findByUserId(Long userId) {
		return customerRepo.findByUserId(userId);
	}

	@Override
	public List<Customer> findScoped(Long orgId, Long userId) {
		return customerRepo.findScoped(orgId, userId);
	}


	public Customer saveUpdateCustomer(CustomerHistoryDTO dto) throws Exception {

		Customer customerObj = dto.getCustomer().getCustomerId() != null ? this.getReferenceById(dto.getCustomer().getCustomerId()) : new Customer();

		if(appUtil.isEmptyOrNull(customerObj.getCustomerId())){

			Example<Customer> example = Example.of(customerObj);

			AuthenticatedUser user = requestUtil.getCurrentUser();
			customerObj.setUserId(user.getUserId());
			if (dto.getCustomer().getContact() != null) {
				customerObj.setContact(dto.getCustomer().getContact());
			}
			if (dto.getCustomer().getName() != null) {
				customerObj.setName(dto.getCustomer().getName());
			}

			customerObj = this.findOne(example).orElse(customerObj);

			if(appUtil.isEmptyOrNull(customerObj.getCustomerId())){ 
				customerObj.setDueAmount(customerObj.getDueAmount() == null ? dto.getCustomer().getDueAmount() : customerObj.getDueAmount() +  dto.getCustomer().getDueAmount() );
			} else {
				customerObj.setDueAmount(customerObj.getDueAmount() == null ? dto.getCustomer().getDueAmount() : customerObj.getDueAmount() + dto.getCustomer().getDueAmount());
			}

			if (dto.getCustomer().getDueDate() != null) {
				customerObj.setDueDate(dto.getCustomer().getDueDate());
			}
		} else {
				customerObj.setDueAmount(customerObj.getDueAmount() == null ? dto.getCustomer().getDueAmount() : customerObj.getDueAmount() + dto.getCustomer().getDueAmount());
		}

		// set the due amount 0 if it is negative
		if (dto.getCustomer().getDueAmount() != null && dto.getCustomer().getDueAmount() < 0) {
			customerObj.setDueAmount(dto.getCustomer().getDueAmount() * -1);
		} else {
			customerObj.setDueAmount(0.0F);
		}

		customerObj.setDated(LocalDateTime.now());
		customerObj.setUpdated(LocalDateTime.now());
		AuthenticatedUser actor = requestUtil.getCurrentUser();
		if (actor != null) {
			if (customerObj.getUserId() == null) customerObj.setUserId(actor.getUserId()); // audit
			customerObj.setOrganizationId(actor.getOrganizationId());                       // tenant scope
		}
		this.save(customerObj);

		return customerObj;
	}

}