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

import com.persistence.Repo.education.GradeRepo;
import com.persistence.model.education.Grade;
import com.service.UserService;

@Service
@Transactional
public class GradeService implements IGradeService {

    @Autowired
    UserService userService;
    
    @Autowired
    GradeRepo gradeRepo;

	@Override
	public List<Grade> findAll() {
		// TODO Auto-generated method stub
		return gradeRepo.findAll();
	}

	@Override
	public List<Grade> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return gradeRepo.findAll(sort);
	}

	@Override
	public List<Grade> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return gradeRepo.findAllById(ids);
	}

	@Override
	public <S extends Grade> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return gradeRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		gradeRepo.flush();
	}

	@Override
	public <S extends Grade> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return gradeRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Grade> entities) {
		// TODO Auto-generated method stub
		gradeRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		gradeRepo.deleteAllInBatch();
	}

	@Override
	public Grade getOne(Long id) {
		// TODO Auto-generated method stub
		return gradeRepo.getOne(id);
	}

	@Override
	public <S extends Grade> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return gradeRepo.findAll(example);
	}

	@Override
	public <S extends Grade> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return gradeRepo.findAll(example, sort);
	}

	@Override
	public Page<Grade> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return gradeRepo.findAll(pageable);
	}

	@Override
	public <S extends Grade> S save(S entity) {
		// TODO Auto-generated method stub
		return gradeRepo.save(entity);
	}

	@Override
	public Optional<Grade> findById(Long id) {
		// TODO Auto-generated method stub
		return gradeRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return gradeRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return gradeRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		gradeRepo.deleteById(id);
	}

	@Override
	public void delete(Grade entity) {
		// TODO Auto-generated method stub
		gradeRepo.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends Grade> entities) {
		// TODO Auto-generated method stub
		gradeRepo.deleteAll();
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		gradeRepo.deleteAll();
	}

	@Override
	public <S extends Grade> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return gradeRepo.findOne(example);
	}

	@Override
	public <S extends Grade> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return gradeRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Grade> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return gradeRepo.count(example);
	}

	@Override
	public <S extends Grade> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return gradeRepo.exists(example);
	}

	@Override
	public int updateStatus(String status, String id) {
		// TODO Auto-generated method stub
		return gradeRepo.updateStatus(status, id);
	}


    
}