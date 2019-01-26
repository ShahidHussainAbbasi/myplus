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

import com.persistence.Repo.business.ItemRepo;
import com.persistence.model.business.Item;
import com.service.UserService;

@Service
@Transactional
public class ItemService implements IItemService {

    @Autowired
    UserService userService;
    
    @Autowired
    ItemRepo itemRepo;

	@Override
	public List<Item> findAll() {
		// TODO Auto-generated method stub
		return itemRepo.findAll();
	}

	@Override
	public List<Item> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return itemRepo.findAll(sort);
	}

	@Override
	public List<Item> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return itemRepo.findAllById(ids);
	}

	@Override
	public <S extends Item> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return itemRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		itemRepo.flush();
	}

	@Override
	public <S extends Item> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return itemRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Item> entities) {
		// TODO Auto-generated method stub
		itemRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		itemRepo.deleteAllInBatch();
	}

	@Override
	public Item getOne(Long id) {
		// TODO Auto-generated method stub
		return itemRepo.getOne(id);
	}

	@Override
	public <S extends Item> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return itemRepo.findAll(example);
	}

	@Override
	public <S extends Item> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return itemRepo.findAll(example,sort);
	}

	@Override
	public Page<Item> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return itemRepo.findAll(pageable);
	}

	@Override
	public <S extends Item> S save(S entity) {
		// TODO Auto-generated method stub
		return itemRepo.save(entity);
	}

	@Override
	public Optional<Item> findById(Long id) {
		// TODO Auto-generated method stub
		return itemRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return itemRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return itemRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		itemRepo.deleteById(id);
		
	}

	@Override
	public void delete(Item entity) {
		// TODO Auto-generated method stub
		itemRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Item> entities) {
		// TODO Auto-generated method stub
		itemRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		itemRepo.deleteAll();
	}

	@Override
	public <S extends Item> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return itemRepo.findOne(example);
	}

	@Override
	public <S extends Item> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return itemRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Item> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return itemRepo.count(example);
	}

	@Override
	public <S extends Item> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return itemRepo.exists(example);
	}

}