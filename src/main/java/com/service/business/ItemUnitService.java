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

import com.persistence.Repo.business.ItemUnitRepo;
import com.persistence.model.business.ItemUnit;
import com.service.UserService;

@Service
@Transactional
public class ItemUnitService implements IItemUnitService {

    @Autowired
    UserService userService;
    
    @Autowired
    ItemUnitRepo itemUnitRepo;

	@Override
	public List<ItemUnit> findAll() {
		// TODO Auto-generated method stub
		return itemUnitRepo.findAll();
	}

	@Override
	public List<ItemUnit> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return itemUnitRepo.findAll(sort);
	}

	@Override
	public List<ItemUnit> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return itemUnitRepo.findAllById(ids);
	}

	@Override
	public <S extends ItemUnit> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return itemUnitRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		itemUnitRepo.flush();
	}

	@Override
	public <S extends ItemUnit> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return itemUnitRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<ItemUnit> entities) {
		// TODO Auto-generated method stub
		itemUnitRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		itemUnitRepo.deleteAllInBatch();
	}

	@Override
	public ItemUnit getOne(Long id) {
		// TODO Auto-generated method stub
		return itemUnitRepo.getOne(id);
	}

	@Override
	public <S extends ItemUnit> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return itemUnitRepo.findAll(example);
	}

	@Override
	public <S extends ItemUnit> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return itemUnitRepo.findAll(example,sort);
	}

	@Override
	public Page<ItemUnit> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return itemUnitRepo.findAll(pageable);
	}

	@Override
	public <S extends ItemUnit> S save(S entity) {
		// TODO Auto-generated method stub
		return itemUnitRepo.save(entity);
	}

	@Override
	public Optional<ItemUnit> findById(Long id) {
		// TODO Auto-generated method stub
		return itemUnitRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return itemUnitRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return itemUnitRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		itemUnitRepo.deleteById(id);
		
	}

	@Override
	public void delete(ItemUnit entity) {
		// TODO Auto-generated method stub
		itemUnitRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends ItemUnit> entities) {
		// TODO Auto-generated method stub
		itemUnitRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		itemUnitRepo.deleteAll();
	}

	@Override
	public <S extends ItemUnit> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return itemUnitRepo.findOne(example);
	}

	@Override
	public <S extends ItemUnit> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return itemUnitRepo.findAll(example, pageable);
	}

	@Override
	public <S extends ItemUnit> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return itemUnitRepo.count(example);
	}

	@Override
	public <S extends ItemUnit> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return itemUnitRepo.exists(example);
	}

}