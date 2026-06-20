package com.myplus.business_service.service;

import java.time.LocalDateTime;
import java.util.Date;
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

import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.repository.ItemRepo;
import com.myplus.commerce.domain.InvoiceNumbers;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Sell;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.ObjectMapperUtils;
import com.myplus.business_service.util.RequestUtil;

@Service
@Transactional
public class CustomerHistoryService implements ICustomerHistoryService {

    @Autowired
    ItemRepo itemRepo;

	@Autowired
	RequestUtil requestUtil;
    
	@Autowired
	CustomerHistoryRepo CustomerHistoryRepo;

    @Autowired
    private AppUtil appUtil;




	public List<CustomerHistory> findAll() {
		return CustomerHistoryRepo.findAll();
	}

	public List<CustomerHistory> findAll(Sort sort) {
		return CustomerHistoryRepo.findAll(sort);
	}

	public List<CustomerHistory> findAllById(Iterable<Long> ids) {
		return CustomerHistoryRepo.findAllById(ids);
	}

	public <S extends CustomerHistory> List<S> saveAll(Iterable<S> entities) {
		return CustomerHistoryRepo.saveAll(entities);
	}

	public void flush() {
		CustomerHistoryRepo.flush();
	}

	public <S extends CustomerHistory> S saveAndFlush(S entity) {
		return CustomerHistoryRepo.saveAndFlush(entity);
	}

	public <S extends CustomerHistory> List<S> saveAllAndFlush(Iterable<S> entities) {
		return CustomerHistoryRepo.saveAllAndFlush(entities);
	}

	public void deleteAllInBatch(Iterable<CustomerHistory> entities) {
		CustomerHistoryRepo.deleteAllInBatch(entities);
	}

	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		CustomerHistoryRepo.deleteAllByIdInBatch(ids);
	}

	public void deleteAllInBatch() {
		CustomerHistoryRepo.deleteAllInBatch();
	}


	public CustomerHistory getReferenceById(Long id) {
		return CustomerHistoryRepo.getReferenceById(id);
	}

	public <S extends CustomerHistory> List<S> findAll(Example<S> example) {
		return CustomerHistoryRepo.findAll(example);
	}

	public <S extends CustomerHistory> List<S> findAll(Example<S> example, Sort sort) {
		return CustomerHistoryRepo.findAll(example, sort);
	}

	public Page<CustomerHistory> findAll(Pageable pageable) {
		return CustomerHistoryRepo.findAll(pageable);
	}

	public <S extends CustomerHistory> S save(S entity) {
		return CustomerHistoryRepo.save(entity);
	}

	public Optional<CustomerHistory> findById(Long id) {
		return CustomerHistoryRepo.findById(id);
	}

	public boolean existsById(Long id) {
		return CustomerHistoryRepo.existsById(id);
	}

	public long count() {
		return CustomerHistoryRepo.count();
	}

	public void deleteById(Long id) {
		CustomerHistoryRepo.deleteById(id);
	}

	public void delete(CustomerHistory entity) {
		CustomerHistoryRepo.delete(entity);
	}

	public void deleteAllById(Iterable<? extends Long> ids) {
		CustomerHistoryRepo.deleteAllById(ids);
	}

	public void deleteAll(Iterable<? extends CustomerHistory> entities) {
		CustomerHistoryRepo.deleteAll(entities);
	}

	public void deleteAll() {
		CustomerHistoryRepo.deleteAll();
	}

	public <S extends CustomerHistory> Optional<S> findOne(Example<S> example) {
		return CustomerHistoryRepo.findOne(example);
	}

	public <S extends CustomerHistory> Page<S> findAll(Example<S> example, Pageable pageable) {
		return CustomerHistoryRepo.findAll(example, pageable);
	}

	public <S extends CustomerHistory> long count(Example<S> example) {
		return CustomerHistoryRepo.count(example);
	}

	public <S extends CustomerHistory> boolean exists(Example<S> example) {
		return CustomerHistoryRepo.exists(example);
	}

	public <S extends CustomerHistory, R> R findBy(Example<S> example,
			Function<FetchableFluentQuery<S>, R> queryFunction) {
		return CustomerHistoryRepo.findBy(example, queryFunction);
	}

	public CustomerHistory getOne(Long id) {
		return CustomerHistoryRepo.getOne(id);
	}

	public CustomerHistory getById(Long id) {
		return CustomerHistoryRepo.getById(id);
	}

	public List<CustomerHistory> findByUserIdAndDateRange(Long userId, LocalDateTime sd, LocalDateTime ed) {
		return CustomerHistoryRepo.findByUserIdAndDateRange(userId, sd, ed);
	}

	public CustomerHistory saveUpdateCustomerHistory(CustomerHistoryDTO dto) {
		CustomerHistory customerHistoryObj = dto.getCustomer_history_id() != null ? this.getReferenceById(dto.getCustomer_history_id()) : new CustomerHistory();

		if (appUtil.isEmptyOrNull(dto.getCustomer())) {
			return customerHistoryObj;
		}
		
		AuthenticatedUser user = requestUtil.getCurrentUser();
		customerHistoryObj = ObjectMapperUtils.map(dto.getCustomer(), CustomerHistory.class);
		customerHistoryObj.setUserId(user.getUserId());                       // audit
		customerHistoryObj.setOrganizationId(user.getOrganizationId());       // tenant scope
		customerHistoryObj.setDated(LocalDateTime.now());
		customerHistoryObj.setUpdated(LocalDateTime.now());
		customerHistoryObj.setDueAmount(dto.getDueAmount() == null ? dto.getCustomer().getDueAmount() : dto.getDueAmount());
		customerHistoryObj.setPaidAmount(dto.getPaidAmount() == null ? dto.getCustomer().getPaidAmount() : dto.getPaidAmount());

		// Assign a per-org invoice number for a NEW sale (slice 22). MAX+1 within the addSell @Transactional;
		// unique(organization_id, invoice_seq) guarantees no duplicate can commit. Skip if no active org.
		if (customerHistoryObj.getCustomer_history_id() == null
				&& customerHistoryObj.getInvoiceSeq() == null
				&& user.getOrganizationId() != null) {
			long seq = CustomerHistoryRepo.maxInvoiceSeqForOrg(user.getOrganizationId()) + 1;
			customerHistoryObj.setInvoiceSeq(seq);
			customerHistoryObj.setInvoiceNo(InvoiceNumbers.format(seq));
		}

		// this.save(customerHistoryObj);

		return customerHistoryObj;
		
		
		}

	}