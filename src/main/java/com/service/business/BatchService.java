package com.service.business;

import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.business.BatchRepo;
import com.persistence.model.business.Stock;
import com.web.dto.business.PurchaseDTO;
import com.web.util.AppUtil;
import com.web.util.RequestUtil;

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

	@Override
	public List<Stock> findAll() {
		// TODO Auto-generated method stub
		return batchRepo.findAll();
	}

	@Override
	public List<Stock> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return batchRepo.findAll(sort);
	}

	@Override
	public List<Stock> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return batchRepo.findAllById(ids);
	}

	@Override
	public <S extends Stock> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return batchRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		batchRepo.flush();
	}

	@Override
	public <S extends Stock> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return batchRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Stock> entities) {
		// TODO Auto-generated method stub
		batchRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		batchRepo.deleteAllInBatch();
	}

	@Override
	public Stock getOne(Long id) {
		// TODO Auto-generated method stub
		return batchRepo.getOne(id);
	}

	@Override
	public <S extends Stock> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return batchRepo.findAll(example);
	}

	@Override
	public <S extends Stock> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return batchRepo.findAll(example,sort);
	}

	@Override
	public Page<Stock> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return batchRepo.findAll(pageable);
	}

	@Override
	public <S extends Stock> S save(S entity) {
		// TODO Auto-generated method stub
		return batchRepo.save(entity);
	}

	@Override
	public Optional<Stock> findById(Long id) {
		// TODO Auto-generated method stub
		return batchRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return batchRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return batchRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		batchRepo.deleteById(id);
		
	}

	@Override
	public void delete(Stock entity) {
		// TODO Auto-generated method stub
		batchRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Stock> entities) {
		// TODO Auto-generated method stub
		batchRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		batchRepo.deleteAll();
	}

	@Override
	public <S extends Stock> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return batchRepo.findOne(example);
	}

	@Override
	public <S extends Stock> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return batchRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Stock> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return batchRepo.count(example);
	}

	@Override
	public <S extends Stock> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return batchRepo.exists(example);
	}

	@Override
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
}