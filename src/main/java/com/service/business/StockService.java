package com.service.business;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import jakarta.transaction.Transactional;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.stereotype.Service;

import com.persistence.Repo.business.StockRepo;
import com.persistence.model.business.Sell;
import com.persistence.model.business.Stock;
import com.web.dto.business.PurchaseDTO;
import com.web.util.AppUtil;
import com.web.util.RequestUtil;

@Service
@Transactional
public class StockService implements IStockService {

    @Autowired
    StockRepo repo;

	@Autowired
	RequestUtil requestUtil;

	@Autowired
	AppUtil appUtil;
	
	ModelMapper modelMapper = new ModelMapper();
    
    
	@Override
	public List<Stock> findAll() {
		// TODO Auto-generated method stub
		return repo.findAll();
	}

	@Override
	public List<Stock> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return repo.findAll(sort);
	}

	@Override
	public List<Stock> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return repo.findAllById(ids);
	}

	@Override
	public <S extends Stock> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return repo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		repo.flush();
	}

	@Override
	public <S extends Stock> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return repo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Stock> entities) {
		// TODO Auto-generated method stub
		repo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		repo.deleteAllInBatch();
	}

	@Override
	public Stock getOne(Long id) {
		// TODO Auto-generated method stub
		return repo.getReferenceById(id);
	}

	@Override
	public <S extends Stock> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return repo.findAll(example);
	}

	@Override
	public <S extends Stock> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return repo.findAll(example,sort);
	}

	@Override
	public Page<Stock> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return repo.findAll(pageable);
	}

	@Override
	public <S extends Stock> S save(S entity) {
		// TODO Auto-generated method stub
		return repo.save(entity);
	}

	@Override
	public Optional<Stock> findById(Long id) {
		// TODO Auto-generated method stub
		return repo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return repo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return repo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		repo.deleteById(id);
		
	}

	@Override
	public void delete(Stock entity) {
		// TODO Auto-generated method stub
		repo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Stock> entities) {
		// TODO Auto-generated method stub
		repo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		repo.deleteAll();
	}

	@Override
	public <S extends Stock> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return repo.findOne(example);
	}

	@Override
	public <S extends Stock> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return repo.findAll(example, pageable);
	}

	@Override
	public <S extends Stock> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return repo.count(example);
	}

	@Override
	public <S extends Stock> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return repo.exists(example);
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
	public Stock updateStock(PurchaseDTO dto) throws Exception {
 
		Optional<Stock> optional = this.checkStock(dto.getItemId());
		if(!optional.isPresent()) {
			return new Stock();
		}
	
		Stock stock = optional.get();

		modelMapper.addConverter(appUtil.stringToLocalDateIgnoreEmptyOrNull);
		modelMapper.addConverter(appUtil.stringToLocalDateTimeIgnoreEmptyOrNull);
		stock = modelMapper.map(dto.getStock(), Stock.class);
		stock.setUserId(dto.getUserId());
		stock.setUserType(dto.getUserType());
		stock.setItemId(dto.getItemId());


		Optional<Stock> existing = this.findByItemId(dto.getItemId());
    	if (existing.isPresent()) {
        	stock.setStockId(existing.get().getStockId());
    	}

		if (appUtil.isEmptyOrNull(dto.getPurchaseId()) || dto.getPurchaseId() <= 0) {
			stock.setStock(stock.getStock()==null? dto.getQuantity() : stock.getStock() + dto.getQuantity()); // only stock need to update
		} else {
			stock.setStock(dto.getQuantity());
		}

		this.saveAndFlush(stock);

		return stock;
	}		

	private Optional<Stock> checkStock(long itemId) {
		Stock stock = new Stock();
		stock.setUserId(requestUtil.getCurrentUser().getId());
		stock.setItemId(itemId);
        Example<Stock> example = Example.of(stock);
		return Optional.ofNullable(this.findOne(example).orElse(new Stock()));
	}

	@Override
	public Stock updateStock(Sell dto) {
		Float quantity = dto.getQuantity();
		Stock obj = new Stock();
		
		obj.setUserId(requestUtil.getCurrentUser().getId());
		// obj.setBatchNo(dto.getStock().getBatchNo());
		if(!appUtil.isEmptyOrNull(dto.getStock().getBatchNo())) {
			obj.setBatchNo(dto.getStock().getBatchNo());
		}
		obj.setItemId(dto.getStock().getItemId());
        Example<Stock> example = Example.of(obj);
		Stock stockTemp = this.findAll(example).get(0);
		if(appUtil.isEmptyOrNull(stockTemp) || stockTemp.getStock() == null || stockTemp.getStock() < quantity || stockTemp.getStock() <= 0) {
			// logger.error("Stock is not available for item id: " + dto.getStock().getItemId() + " and batch no: " + dto.getStock().getBatchNo());
			throw new RuntimeException("Stock is not available for item id: " + dto.getStock().getItemId());
		}
		stockTemp = modelMapper.map(dto.getStock(), Stock.class);
		quantity = stockTemp.getStock() - quantity;
		stockTemp.setStock(quantity);
		stockTemp.setUserId(requestUtil.getCurrentUser().getId());
		
		stockTemp.setItemId(dto.getStock().getItemId());
		Optional<Stock> existing = this.findByItemId(dto.getStock().getItemId());
    	if (existing.isPresent()) {
        	stockTemp.setStockId(existing.get().getStockId());
    	}
		// this.save(stockTemp);
		return stockTemp;
		
	}

	@Override
	public Set<String> getItemBatch(Long userId, Long itemId) {
		// TODO Auto-generated method stub
		return repo.getItemBatch(userId, itemId);
	}

	@Override
	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllByIdInBatch'");
	}

	@Override
	public void deleteAllInBatch(Iterable<Stock> entities) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllInBatch'");
	}

	@Override
	public Stock getById(Long id) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'getById'");
	}

	@Override
	public Stock getReferenceById(Long id) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'getReferenceById'");
	}

	@Override
	public <S extends Stock> List<S> saveAllAndFlush(Iterable<S> entities) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'saveAllAndFlush'");
	}

	@Override
	public void deleteAllById(Iterable<? extends Long> ids) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllById'");
	}

	@Override
	public <S extends Stock, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'findBy'");
	}

	@Override
	public Optional<Stock> findByItemId(Long itemId) {
        return repo.findByItemId(itemId);
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