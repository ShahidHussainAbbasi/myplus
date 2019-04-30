package com.service.education;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.education.FeeCollectionRepo;
import com.persistence.model.education.FeeCollection;

@Service
@Transactional
public class FeeCollectionService implements IFeeCollectionService{


    @Autowired
    FeeCollectionRepo feeCollectionRep;
    
	@Override
	public List<FeeCollection> findAll() {
		// TODO Auto-generated method stub
		return feeCollectionRep.findAll();
	}

	@Override
	public List<FeeCollection> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return feeCollectionRep.findAll(sort);
	}

	@Override
	public List<FeeCollection> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return feeCollectionRep.findAllById(ids);
	}

	@Override
	public <S extends FeeCollection> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return feeCollectionRep.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		feeCollectionRep.flush();
	}

	@Override
	public <S extends FeeCollection> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return feeCollectionRep.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<FeeCollection> entities) {
		// TODO Auto-generated method stub
		feeCollectionRep.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		feeCollectionRep.deleteAllInBatch();
	}

	@Override
	public FeeCollection getOne(Long id) {
		// TODO Auto-generated method stub
		return feeCollectionRep.getOne(id);
	}

	@Override
	public <S extends FeeCollection> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return feeCollectionRep.findAll(example);
	}

	@Override
	public <S extends FeeCollection> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return feeCollectionRep.findAll(example, sort);
	}

	@Override
	public Page<FeeCollection> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return feeCollectionRep.findAll(pageable);
	}

	@Override
	public <S extends FeeCollection> S save(S entity) {
		// TODO Auto-generated method stub
		return feeCollectionRep.save(entity);
	}

	@Override
	public Optional<FeeCollection> findById(Long id) {
		// TODO Auto-generated method stub
		return feeCollectionRep.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return feeCollectionRep.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return feeCollectionRep.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		feeCollectionRep.deleteById(id);
	}

	@Override
	public void delete(FeeCollection entity) {
		// TODO Auto-generated method stub
		feeCollectionRep.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends FeeCollection> entities) {
		// TODO Auto-generated method stub
		feeCollectionRep.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		feeCollectionRep.deleteAll();
	}

	@Override
	public <S extends FeeCollection> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return feeCollectionRep.findOne(example);
	}

	@Override
	public <S extends FeeCollection> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return feeCollectionRep.findAll(example, pageable);
	}

	@Override
	public <S extends FeeCollection> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return feeCollectionRep.count(example);
	}

	@Override
	public <S extends FeeCollection> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return feeCollectionRep.exists(example);
	}

	@Override
	public FeeCollection findStudent(String enrollNo, String name,Long userId) {
		return findStudent(enrollNo, name,userId);
	}

	@Override
	public List<FeeCollection> findFCByStartDate(String enroll_no, LocalDate sd, Long userId) {
		return feeCollectionRep.findFCByStartDate(enroll_no, sd, userId);
	}

	@Override
	public List<FeeCollection> findFCByEndDate(String enroll_no, LocalDate ed, Long userId) {
		return feeCollectionRep.findFCByEndDate(enroll_no, ed, userId);
	}

	@Override
	public List<FeeCollection> findFCByDates(String enroll_no, LocalDate sd, LocalDate ed, Long userId) {
		return feeCollectionRep.findFCByDates(enroll_no, sd, ed, userId);
	}

    
}