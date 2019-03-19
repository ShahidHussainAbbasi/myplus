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

import com.persistence.Repo.education.AttendaceRepo;
import com.persistence.model.education.Attendance;

@Service
@Transactional
public class AttendanceService implements IAttendanceService{


    @Autowired
    AttendaceRepo attandanceRep;
    
	@Override
	public List<Attendance> findAll() {
		// TODO Auto-generated method stub
		return attandanceRep.findAll();
	}

	@Override
	public List<Attendance> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return attandanceRep.findAll(sort);
	}

	@Override
	public List<Attendance> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return attandanceRep.findAllById(ids);
	}

	@Override
	public <S extends Attendance> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return attandanceRep.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		attandanceRep.flush();
	}

	@Override
	public <S extends Attendance> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return attandanceRep.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Attendance> entities) {
		// TODO Auto-generated method stub
		attandanceRep.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		attandanceRep.deleteAllInBatch();
	}

	@Override
	public Attendance getOne(Long id) {
		// TODO Auto-generated method stub
		return attandanceRep.getOne(id);
	}

	@Override
	public <S extends Attendance> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return attandanceRep.findAll(example);
	}

	@Override
	public <S extends Attendance> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return attandanceRep.findAll(example, sort);
	}

	@Override
	public Page<Attendance> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return attandanceRep.findAll(pageable);
	}

	@Override
	public <S extends Attendance> S save(S entity) {
		// TODO Auto-generated method stub
		return attandanceRep.save(entity);
	}

	@Override
	public Optional<Attendance> findById(Long id) {
		// TODO Auto-generated method stub
		return attandanceRep.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return attandanceRep.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return attandanceRep.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		attandanceRep.deleteById(id);
	}

	@Override
	public void delete(Attendance entity) {
		// TODO Auto-generated method stub
		attandanceRep.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends Attendance> entities) {
		// TODO Auto-generated method stub
		attandanceRep.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		attandanceRep.deleteAll();
	}

	@Override
	public <S extends Attendance> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return attandanceRep.findOne(example);
	}

	@Override
	public <S extends Attendance> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return attandanceRep.findAll(example, pageable);
	}

	@Override
	public <S extends Attendance> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return attandanceRep.count(example);
	}

	@Override
	public <S extends Attendance> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return attandanceRep.exists(example);
	}

    
}