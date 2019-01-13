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

import com.persistence.Repo.education.SchoolOwnerRepo;
import com.persistence.model.education.SchoolOwner;
import com.service.UserService;

@Service
@Transactional
public class SchoolOwnerService implements ISchoolOwnerService {

    @Autowired
    UserService userService;
    
    @Autowired
    SchoolOwnerRepo schoolOwnerRepo;

	@Override
	public List<SchoolOwner> findAll() {
		// TODO Auto-generated method stub
		return schoolOwnerRepo.findAll();
	}

	@Override
	public List<SchoolOwner> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<SchoolOwner> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends SchoolOwner> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public <S extends SchoolOwner> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void deleteInBatch(Iterable<SchoolOwner> entities) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public SchoolOwner getOne(Long id) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends SchoolOwner> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return schoolOwnerRepo.findAll(example);
	}

	@Override
	public <S extends SchoolOwner> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Page<SchoolOwner> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends SchoolOwner> S save(S entity) {
		// TODO Auto-generated method stub
		return schoolOwnerRepo.save(entity);
	}

	@Override
	public Optional<SchoolOwner> findById(Long id) {
		// TODO Auto-generated method stub
		return schoolOwnerRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return schoolOwnerRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		schoolOwnerRepo.deleteById(id);
		
	}

	@Override
	public void delete(SchoolOwner entity) {
		// TODO Auto-generated method stub
		schoolOwnerRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends SchoolOwner> entities) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public <S extends SchoolOwner> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return schoolOwnerRepo.findOne(example);
	}

	@Override
	public <S extends SchoolOwner> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends SchoolOwner> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public <S extends SchoolOwner> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return schoolOwnerRepo.exists(example);
	}


    
}