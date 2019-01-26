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

import com.persistence.Repo.education.StaffRepo;
import com.persistence.model.education.Staff;
import com.service.UserService;

@Service
@Transactional
public class StaffService implements IStaffService {

	@Autowired
	UserService userService;

	@Autowired
	StaffRepo staffRepo;

	@Override
	public List<Staff> findAll() {
		// TODO Auto-generated method stub
		return staffRepo.findAll();
	}

	@Override
	public List<Staff> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return staffRepo.findAll(sort);
	}

	@Override
	public List<Staff> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return staffRepo.findAllById(ids);
	}

	@Override
	public <S extends Staff> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return staffRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		staffRepo.flush();
	}

	@Override
	public <S extends Staff> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return staffRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Staff> entities) {
		// TODO Auto-generated method stub
		staffRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		staffRepo.deleteAllInBatch();
	}

	@Override
	public Staff getOne(Long id) {
		// TODO Auto-generated method stub
		return staffRepo.getOne(id);
	}

	@Override
	public <S extends Staff> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return staffRepo.findAll(example);
	}

	@Override
	public <S extends Staff> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return staffRepo.findAll(example, sort);
	}

	@Override
	public Page<Staff> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return staffRepo.findAll(pageable);
	}

	@Override
	public <S extends Staff> S save(S entity) {
		// TODO Auto-generated method stub
		return staffRepo.save(entity);
	}

	@Override
	public Optional<Staff> findById(Long id) {
		// TODO Auto-generated method stub
		return staffRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return staffRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return staffRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		staffRepo.deleteById(id);
	}

	@Override
	public void delete(Staff entity) {
		// TODO Auto-generated method stub
		staffRepo.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends Staff> entities) {
		// TODO Auto-generated method stub
		staffRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		staffRepo.deleteAll();
	}

	@Override
	public <S extends Staff> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return staffRepo.findOne(example);
	}

	@Override
	public <S extends Staff> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return staffRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Staff> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return staffRepo.count(example);
	}

	@Override
	public <S extends Staff> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return staffRepo.exists(example);
	}

	@Override
	public int updateStatus(String status, String id) {
		// TODO Auto-generated method stub
		return staffRepo.updateStatus(status, id);
	}

}