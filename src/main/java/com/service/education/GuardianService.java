package com.service.education;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.education.GuardianRepo;
import com.persistence.model.education.Guardian;
import com.service.UserService;

@Service
@Transactional
public class GuardianService implements IGuardianService {

    @Autowired
    UserService userService;

    @Autowired
    GuardianRepo guardianRepo;
    
	@Override
	public List<Guardian> findAll() {
		// TODO Auto-generated method stub
		return guardianRepo.findAll();
	}

	@Override
	public List<Guardian> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return guardianRepo.findAll(sort);
	}

	@Override
	public List<Guardian> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return guardianRepo.findAllById(ids);
	}

	@Override
	public <S extends Guardian> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return guardianRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		guardianRepo.flush();
	}

	@Override
	public <S extends Guardian> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return guardianRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Guardian> entities) {
		// TODO Auto-generated method stub
		guardianRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		guardianRepo.deleteAllInBatch();
	}

	@Override
	public Guardian getOne(Long id) {
		// TODO Auto-generated method stub
		return guardianRepo.getOne(id);
	}

	@Override
	public <S extends Guardian> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return guardianRepo.findAll(example);
	}

	@Override
	public <S extends Guardian> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return guardianRepo.findAll(example,sort);
	}

	@Override
	public Page<Guardian> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return guardianRepo.findAll(pageable);
	}

	@Override
	public <S extends Guardian> S save(S entity) {
		// TODO Auto-generated method stub
		return guardianRepo.save(entity);
	}

	@Override
	public Optional<Guardian> findById(Long id) {
		// TODO Auto-generated method stub
		return guardianRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return guardianRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return guardianRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		guardianRepo.deleteById(id);
	}

	@Override
	public void delete(Guardian entity) {
		// TODO Auto-generated method stub
		guardianRepo.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends Guardian> entities) {
		// TODO Auto-generated method stub
		guardianRepo.deleteAll();
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		guardianRepo.deleteAll();
	}

	@Override
	public <S extends Guardian> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return guardianRepo.findOne(example);
	}

	@Override
	public <S extends Guardian> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return guardianRepo.findAll(example,pageable);
	}

	@Override
	public <S extends Guardian> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return guardianRepo.count(example);
	}

	@Override
	public <S extends Guardian> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return guardianRepo.exists(example);
	}

	@Override
	public int updateStatus(String status, String id,Date updated) {
		// TODO Auto-generated method stub
		return guardianRepo.updateStatus(status, id,updated);
	}


    
}