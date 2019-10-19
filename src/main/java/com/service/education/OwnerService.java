package com.service.education;

import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.education.OwnerRepo;
import com.persistence.model.education.Owner;

@Service
@Transactional
public class OwnerService implements IOwnerService {

    @Autowired
    OwnerRepo ownerRepo;

	@Override
	public List<Owner> findAll() {
		// TODO Auto-generated method stub
		return ownerRepo.findAll();
	}

	@Override
	public List<Owner> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return ownerRepo.findAll(sort);
	}

	@Override
	public List<Owner> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return ownerRepo.findAllById(ids);
	}

	@Override
	public <S extends Owner> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return ownerRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		ownerRepo.flush();
	}

	@Override
	public <S extends Owner> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return ownerRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Owner> entities) {
		// TODO Auto-generated method stub
		ownerRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		ownerRepo.deleteAllInBatch();
	}

	@Override
	public Owner getOne(Long id) {
		// TODO Auto-generated method stub
		return ownerRepo.getOne(id);
	}

	@Override
	public <S extends Owner> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return ownerRepo.findAll(example);
	}

	@Override
	public <S extends Owner> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return ownerRepo.findAll(example,sort);
	}

	@Override
	public Page<Owner> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return ownerRepo.findAll(pageable);
	}

	@Override
	public <S extends Owner> S save(S entity) {
		// TODO Auto-generated method stub
		return ownerRepo.save(entity);
	}

	@Override
	public Optional<Owner> findById(Long id) {
		// TODO Auto-generated method stub
		return ownerRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return ownerRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return ownerRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		ownerRepo.deleteById(id);
		
	}

	@Override
	public void delete(Owner entity) {
		// TODO Auto-generated method stub
		ownerRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Owner> entities) {
		// TODO Auto-generated method stub
		ownerRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		ownerRepo.deleteAll();
	}

	@Override
	public <S extends Owner> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return ownerRepo.findOne(example);
	}

	@Override
	public <S extends Owner> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return ownerRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Owner> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return ownerRepo.count(example);
	}

	@Override
	public <S extends Owner> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return ownerRepo.exists(example);
	}

	@Override
	public int updateStatus(String status, String id) {
		// TODO Auto-generated method stub
		return ownerRepo.updateStatus(status, id);
	}


    
}