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

import com.persistence.Repo.education.SchoolRepo;
import com.persistence.model.education.School;
import com.service.UserService;

@Service
@Transactional
public class SchoolService implements ISchoolService {

    @Autowired
    UserService userService;

    @Autowired
    SchoolRepo schoolRepo;
	@Override
	public List<School> findAll() {
		// TODO Auto-generated method stub
		return schoolRepo.findAll();
	}

	@Override
	public List<School> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return schoolRepo.findAll(sort);
	}

	@Override
	public List<School> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return schoolRepo.findAllById(ids);
	}

	@Override
	public <S extends School> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return schoolRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		schoolRepo.flush();
	}

	@Override
	public <S extends School> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return schoolRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<School> entities) {
		// TODO Auto-generated method stub
		schoolRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		schoolRepo.deleteAllInBatch();
	}

	@Override
	public School getOne(Long id) {
		// TODO Auto-generated method stub
		return schoolRepo.getOne(id);
	}

	@Override
	public <S extends School> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return schoolRepo.findAll(example);
	}

	@Override
	public <S extends School> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return schoolRepo.findAll(example, sort);
	}

	@Override
	public Page<School> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return schoolRepo.findAll(pageable);
	}

	@Override
	public <S extends School> S save(S entity) {
		// TODO Auto-generated method stub
		return schoolRepo.save(entity);
	}

	@Override
	public Optional<School> findById(Long id) {
		// TODO Auto-generated method stub
		return schoolRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return schoolRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return schoolRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		schoolRepo.deleteById(id);
	}

	@Override
	public void delete(School entity) {
		// TODO Auto-generated method stub
		schoolRepo.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends School> entities) {
		// TODO Auto-generated method stub
		schoolRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		schoolRepo.deleteAll();
	}

	@Override
	public <S extends School> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return schoolRepo.findOne(example);
	}

	@Override
	public <S extends School> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return schoolRepo.findAll(example, pageable);
	}

	@Override
	public <S extends School> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return schoolRepo.count();
	}

	@Override
	public <S extends School> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return schoolRepo.exists(example);
	}

	@Override
	public int updateStatus(String status, String id) {
		// TODO Auto-generated method stub
		return schoolRepo.updateStatus(status, id);
	}


    
}