/*package com.myplus.business_service.service;

import java.util.List;
import java.util.Optional;

import jakarta.transaction.Transactional;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.myplus.business_service.repository.BatchRepo;
import com.myplus.business_service.entity.Stock;
import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.RequestUtil;

@Service
@Transactional
public class BatchService implements IBatchService {
	
	ModelMapper modelMapper = new ModelMapper();

    @Autowired
    BatchRepo batchRepo;

    @Autowired
    IStockService stockService;
    
    @Autowired
    IBatchService batchService;

    @Autowired
    RequestUtil requestUtil;
    
    @Autowired
    AppUtil appUtil;

	public List<Stock> findAll() {
		// TODO Auto-generated method stub
		return batchRepo.findAll();
	}

	public List<Stock> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return batchRepo.findAll(sort);
	}

	public List<Stock> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return batchRepo.findAllById(ids);
	}

	public <S extends Stock> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return batchRepo.saveAll(entities);
	}

	public void flush() {
		// TODO Auto-generated method stub
		batchRepo.flush();
	}

	public <S extends Stock> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return batchRepo.saveAndFlush(entity);
	}

	public void deleteInBatch(Iterable<Stock> entities) {
		// TODO Auto-generated method stub
		batchRepo.deleteInBatch(entities);
	}

	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		batchRepo.deleteAllInBatch();
	}

	public Stock getOne(Long id) {
		// TODO Auto-generated method stub
		return batchRepo.getOne(id);
	}

	public <S extends Stock> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return batchRepo.findAll(example);
	}

	public <S extends Stock> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return batchRepo.findAll(example,sort);
	}

	public Page<Stock> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return batchRepo.findAll(pageable);
	}

	public <S extends Stock> S save(S entity) {
		// TODO Auto-generated method stub
		return batchRepo.save(entity);
	}

	public Optional<Stock> findById(Long id) {
		// TODO Auto-generated method stub
		return batchRepo.findById(id);
	}

	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return batchRepo.existsById(id);
	}

	public long count() {
		// TODO Auto-generated method stub
		return batchRepo.count();
	}

	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		batchRepo.deleteById(id);
		
	}

	public void delete(Stock entity) {
		// TODO Auto-generated method stub
		batchRepo.delete(entity);
		
	}

	public void deleteAll(Iterable<? extends Stock> entities) {
		// TODO Auto-generated method stub
		batchRepo.deleteAll(entities);
	}

	public void deleteAll() {
		// TODO Auto-generated method stub
		batchRepo.deleteAll();
	}

	public <S extends Stock> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return batchRepo.findOne(example);
	}

	public <S extends Stock> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return batchRepo.findAll(example, pageable);
	}

	public <S extends Stock> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return batchRepo.count(example);
	}

	public <S extends Stock> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return batchRepo.exists(example);
	}

	public void updateBatch(PurchaseDTO dto) {
//		if(!appUtil.isEmptyOrNull(dto.getId())){
//			Stock obj = new Stock();
//			obj.setBatchNo(dto.getBatchNo());
//			obj.setBitemId(dto.getItemId());
//	        Example<Stock> example = Example.of(obj);
//			Optional<Stock> optional = this.findOne(example);
//			if(optional.isPresent() && appUtil.isEmptyOrNull(dto.getQuantity())) {
//					obj.setBstock(dto.getQuantity());
//			}
//		}
//		modelMapper.addConverter(appUtil.localDateTimeToString);
//		modelMapper.addConverter(appUtil.localDateToString);
//		Stock obj = modelMapper.map(dto, Stock.class);
//		obj.setBitemId(dto.getItemId());
//		this.save(obj);
	}
}*/