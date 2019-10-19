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

import com.persistence.Repo.education.SubjectRepo;
import com.persistence.model.education.Subject;

@Service
@Transactional
public class SubjectService implements ISubjectService {

    @Autowired
    SubjectRepo subjectRepo;
    
	@Override
	public List<Subject> findAll() {
		// TODO Auto-generated method stub
		return subjectRepo.findAll();
	}

	@Override
	public List<Subject> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return subjectRepo.findAll(sort);
	}

	@Override
	public List<Subject> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return subjectRepo.findAllById(ids);
	}

	@Override
	public <S extends Subject> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return subjectRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		subjectRepo.flush();
	}

	@Override
	public <S extends Subject> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return subjectRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Subject> entities) {
		// TODO Auto-generated method stub
		subjectRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		subjectRepo.deleteAllInBatch();
	}

	@Override
	public Subject getOne(Long id) {
		// TODO Auto-generated method stub
		return subjectRepo.getOne(id);
	}

	@Override
	public <S extends Subject> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return subjectRepo.findAll(example);
	}

	@Override
	public <S extends Subject> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return subjectRepo.findAll(example, sort);
	}

	@Override
	public Page<Subject> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return subjectRepo.findAll(pageable);
	}

	@Override
	public <S extends Subject> S save(S entity) {
		// TODO Auto-generated method stub
		return subjectRepo.save(entity);
	}

	@Override
	public Optional<Subject> findById(Long id) {
		// TODO Auto-generated method stub
		return subjectRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return subjectRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return subjectRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		subjectRepo.deleteById(id);
	}

	@Override
	public void delete(Subject entity) {
		// TODO Auto-generated method stub
		subjectRepo.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends Subject> entities) {
		// TODO Auto-generated method stub
		subjectRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		subjectRepo.deleteAll();
	}

	@Override
	public <S extends Subject> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return subjectRepo.findOne(example);
	}

	@Override
	public <S extends Subject> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return subjectRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Subject> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return subjectRepo.count(example);
	}

	@Override
	public <S extends Subject> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return subjectRepo.exists(example);
	}

	@Override
	public int updateStatus(String status, String id) {
		// TODO Auto-generated method stub
		return subjectRepo.updateStatus(status, id);
	}

}