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

import com.persistence.Repo.business.ItemTypeRepo;
import com.persistence.model.business.ItemType;
import com.service.IUserService;

@Service
@Transactional
public class ItemTypeService implements IItemTypeService {

    @Autowired
    IUserService userService;
    
    @Autowired
    ItemTypeRepo itemTypeRepo;

	@Override
	public List<ItemType> findAll() {
		// TODO Auto-generated method stub
		return itemTypeRepo.findAll();
	}

	@Override
	public List<ItemType> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return itemTypeRepo.findAll(sort);
	}

	@Override
	public List<ItemType> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return itemTypeRepo.findAllById(ids);
	}

	@Override
	public <S extends ItemType> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return itemTypeRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		itemTypeRepo.flush();
	}

	@Override
	public <S extends ItemType> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return itemTypeRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<ItemType> entities) {
		// TODO Auto-generated method stub
		itemTypeRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		itemTypeRepo.deleteAllInBatch();
	}

	@Override
	public ItemType getOne(Long id) {
		// TODO Auto-generated method stub
		return itemTypeRepo.getOne(id);
	}

	@Override
	public <S extends ItemType> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return itemTypeRepo.findAll(example);
	}

	@Override
	public <S extends ItemType> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return itemTypeRepo.findAll(example,sort);
	}

	@Override
	public Page<ItemType> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return itemTypeRepo.findAll(pageable);
	}

	@Override
	public <S extends ItemType> S save(S entity) {
		// TODO Auto-generated method stub
		return itemTypeRepo.save(entity);
	}

	@Override
	public Optional<ItemType> findById(Long id) {
		// TODO Auto-generated method stub
		return itemTypeRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return itemTypeRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return itemTypeRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		itemTypeRepo.deleteById(id);
		
	}

	@Override
	public void delete(ItemType entity) {
		// TODO Auto-generated method stub
		itemTypeRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends ItemType> entities) {
		// TODO Auto-generated method stub
		itemTypeRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		itemTypeRepo.deleteAll();
	}

	@Override
	public <S extends ItemType> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return itemTypeRepo.findOne(example);
	}

	@Override
	public <S extends ItemType> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return itemTypeRepo.findAll(example, pageable);
	}

	@Override
	public <S extends ItemType> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return itemTypeRepo.count(example);
	}

	@Override
	public <S extends ItemType> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return itemTypeRepo.exists(example);
	}

}