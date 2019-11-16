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

import com.persistence.Repo.business.StockRepo;
import com.persistence.model.business.Stock;
import com.web.dto.business.PurchaseDTO;
import com.web.dto.business.SellDTO;
import com.web.util.AppUtil;
import com.web.util.RequestUtil;

@Service
@Transactional
public class StockService implements IStockService {

    @Autowired
    StockRepo stockRepo;

    @Autowired
    BatchService batchService;

	@Autowired
	RequestUtil requestUtil;

	@Autowired
	AppUtil appUtil;
	
	ModelMapper modelMapper = new ModelMapper();
    
    
	@Override
	public List<Stock> findAll() {
		// TODO Auto-generated method stub
		return stockRepo.findAll();
	}

	@Override
	public List<Stock> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return stockRepo.findAll(sort);
	}

	@Override
	public List<Stock> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return stockRepo.findAllById(ids);
	}

	@Override
	public <S extends Stock> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return stockRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		stockRepo.flush();
	}

	@Override
	public <S extends Stock> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return stockRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Stock> entities) {
		// TODO Auto-generated method stub
		stockRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		stockRepo.deleteAllInBatch();
	}

	@Override
	public Stock getOne(Long id) {
		// TODO Auto-generated method stub
		return stockRepo.getOne(id);
	}

	@Override
	public <S extends Stock> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return stockRepo.findAll(example);
	}

	@Override
	public <S extends Stock> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return stockRepo.findAll(example,sort);
	}

	@Override
	public Page<Stock> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return stockRepo.findAll(pageable);
	}

	@Override
	public <S extends Stock> S save(S entity) {
		// TODO Auto-generated method stub
		return stockRepo.save(entity);
	}

	@Override
	public Optional<Stock> findById(Long id) {
		// TODO Auto-generated method stub
		return stockRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return stockRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return stockRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		stockRepo.deleteById(id);
		
	}

	@Override
	public void delete(Stock entity) {
		// TODO Auto-generated method stub
		stockRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Stock> entities) {
		// TODO Auto-generated method stub
		stockRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		stockRepo.deleteAll();
	}

	@Override
	public <S extends Stock> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return stockRepo.findOne(example);
	}

	@Override
	public <S extends Stock> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return stockRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Stock> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return stockRepo.count(example);
	}

	@Override
	public <S extends Stock> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return stockRepo.exists(example);
	}

/*	@Override
	public void updateStock(PurchaseDTO dto) {
		Stock obj = new Stock();
		Long userId = requestUtil.getCurrentUser().getId();
		obj.setUserId(userId);
		obj.setBatchNo(dto.getBatchNo());
		obj.setSitemId(dto.getItemId());
        Example<Stock> example = Example.of(obj);
		Optional<Stock> optional = this.findOne(example);
		if(optional.isPresent()) {
			Float balance = optional.get().getBalance(); //purchaseService.getOne(dto.getId());
			obj.setBalance(balance + dto.getQuantity());
//			if(objTemp.getBalance() > dto.getQuantity()) {
//				balance = item.getBalance() + (dto.getQuantity() - objTemp.getBalance());
//			}else {
//				balance = item.getStock() - (objTemp.getBalance() - dto.getQuantity());
//			}
		}else {
			obj.setBalance(dto.getQuantity());
		}
		obj.setPurchased(dto.getQuantity());
		obj.setSold(null);
		obj.setDated(LocalDateTime.now());
		obj.setUserId(requestUtil.getCurrentUser().getId());
		this.save(obj);
	}
*/
	@Override
	public Stock updateStock(PurchaseDTO dto) {
		Float stock = dto.getQuantity();
		Long stockId = null;
		Stock obj = new Stock();		
		obj.setUserId(requestUtil.getCurrentUser().getId());
		obj.setBatchNo(appUtil.isEmptyOrNull(dto.getStockDTO().getBatchNo())?"":dto.getStockDTO().getBatchNo());
		obj.setItemId(dto.getItemId());
        Example<Stock> example = Example.of(obj);
		Optional<Stock> optional = this.findOne(example);
		if(optional.isPresent()) {
			Stock stockTemp = optional.get();
			stockId = stockTemp.getStockId(); //purchaseService.getOne(dto.getId());
			if(appUtil.isEmptyOrNull(dto.getPurchaseId())) {//mean new purchase
				stock = stockTemp.getStock() + stock;//5 2
			}else {
				if(stockTemp.getStock() > stock) {
					stock = stockTemp.getStock() + (stock - stockTemp.getStock());//5.2-2
				}else {
					stock = stockTemp.getStock() - (stockTemp.getStock() - stock);//5.2-2
//					stock = stockTemp.getStock() + stock;//5.2-2
				}
			}
		}
		modelMapper.addConverter(appUtil.stringToLocalDateIgnoreEmptyOrNull);
		modelMapper.addConverter(appUtil.stringToLocalDateTimeIgnoreEmptyOrNull);
		obj = modelMapper.map(dto.getStockDTO(), Stock.class);
//		obj.toString();
		obj.setUserId(requestUtil.getCurrentUser().getId());
		obj.setStockId(stockId);
		obj.setItemId(dto.getItemId());
		obj.setStock(stock);
		return obj;
//		if(optional.isPresent()) {
//			Stock objTemp = optional.get(); //purchaseService.getOne(dto.getId());
//			if(objTemp.getBstock() > dto.getQuantity()) {
//				stock = item.getStock() + (dto.getQuantity() - objTemp.getBstock());
//			}else {
//				stock = item.getStock() - (objTemp.getBstock() - dto.getQuantity());
//			}
////			item.setStock(stock);	
//		}

//			dto.setStockDTO(modelMapper.map(obj, StockDTO.class));
////			this.save(obj);
//			dto.setPstockId(obj.getStockId());
		}

	@Override
	public Stock updateStock(SellDTO dto) {
		Float stock = dto.getQuantity();
//		Long stockId = null;
		Stock obj = new Stock();
		
		obj.setUserId(requestUtil.getCurrentUser().getId());
		obj.setBatchNo(dto.getStockDTO().getBatchNo());
		if(!appUtil.isEmptyOrNull(dto.getStockDTO().getBatchNo())) {
			obj.setBatchNo(dto.getStockDTO().getBatchNo());
		}
		obj.setItemId(dto.getItemId());
        Example<Stock> example = Example.of(obj);
		Stock stockTemp = this.findAll(example).get(0);
		if(!appUtil.isEmptyOrNull(stockTemp)) {
//			Stock stockTemp = optional.get();
//			stockId = stockTemp.getStockId(); //purchaseService.getOne(dto.getId());
			if(appUtil.isEmptyOrNull(dto.getSellId())) {//mean new purchase
				stock = stockTemp.getStock() - stock;//5 2
			}else {
				if(stockTemp.getStock() > stock) {
					stock = stockTemp.getStock() - (stockTemp.getStock() - stock);//5.2-2
				}else {
					stock = stockTemp.getStock() + stock;//5.2-2
				}
			}
			stockTemp.setStock(stock);
			return stockTemp;
		}else {
			obj.setStock(stock);
			return obj;
		}
//		modelMapper.addConverter(appUtil.stringToLocalDate);
//		modelMapper.addConverter(appUtil.stringToLocalDateTime);
//		obj = modelMapper.map(dto.getStockDTO(), Stock.class);
//		obj.setUserId(requestUtil.getCurrentUser().getId());
//		obj.setStockId(stockId);
//		obj.setItemId(dto.getItemId());
//		obj.setStock(stock);
//		return obj;
	}
	
/*	@Override
	public List<Stock> updateStock(List<SellDTO> dtos) {
		List<Stock> stocks = new ArrayList<>();
		if(!appUtil.isEmptyOrNull(dtos)) {
			dtos.forEach(dto ->{
				Float stock = dto.getQuantity();
				Long stockId = null;
				Stock obj = new Stock();
				
				obj.setUserId(requestUtil.getCurrentUser().getId());
				obj.setBatchNo(dto.getStockDTO().getBatchNo());
				obj.setItemId(dto.getItemId());
		        Example<Stock> example = Example.of(obj);
				Optional<Stock> optional = this.findOne(example);
				if(optional.isPresent()) {
					Stock stockTemp = optional.get();
					stockId = stockTemp.getStockId(); //purchaseService.getOne(dto.getId());
					if(appUtil.isEmptyOrNull(dto.getSellId())) {//mean new purchase
						stock = stockTemp.getStock() - stock;//5 2
					}else {
						if(stockTemp.getStock() > stock) {
							stock = stockTemp.getStock() - (stockTemp.getStock() - stock);//5.2-2
						}else {
							stock = stockTemp.getStock() + stock;//5.2-2
						}
					}
				}
				modelMapper.addConverter(appUtil.stringToLocalDate);
				modelMapper.addConverter(appUtil.stringToLocalDateTime);
				obj = modelMapper.map(dto.getStockDTO(), Stock.class);
				obj.setUserId(requestUtil.getCurrentUser().getId());
				obj.setStockId(stockId);
				obj.setItemId(dto.getItemId());
				obj.setStock(stock);
				stocks.add(obj);
			});
				
		}
		return stocks;
	}
*/	
}