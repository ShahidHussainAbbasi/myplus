package com.service.abbasiWelfare;

import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.abbasiWelfare.DonatorRepository;
import com.persistence.model.abbasiWelfare.Donator;

@Service
@Transactional
public class DonatorService implements IDonatorService {

	@Autowired
	DonatorRepository donatorRep;
	
	@Override
	public List<Donator> findAll() {
		return donatorRep.findAll();
	}

	@Override
	public List<Donator> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return donatorRep.findAll(sort);
	}

	@Override
	public List<Donator> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return donatorRep.findAllById(ids);
	}

	@Override
	public <S extends Donator> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return donatorRep.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		donatorRep.flush();
	}

	@Override
	public <S extends Donator> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void deleteInBatch(Iterable<Donator> entities) {
		// TODO Auto-generated method stub
		donatorRep.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		donatorRep.deleteAllInBatch();
	}

	@Override
	public Donator getOne(Long id) {
		// TODO Auto-generated method stub
		return donatorRep.getOne(id);
	}

	@Override
	public <S extends Donator> List<S> findAll(Example<S> example) {
		return donatorRep.findAll(example);
	}

	@Override
	public <S extends Donator> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return donatorRep.findAll(example, sort);
	}

	@Override
	public Page<Donator> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return donatorRep.findAll(pageable);
	}

	@Override
	public <S extends Donator> S save(S entity) {
		return donatorRep.save(entity);
	}

	@Override
	public Optional<Donator> findById(Long id) {
		// TODO Auto-generated method stub
		return donatorRep.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return donatorRep.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return donatorRep.count();
	}

	@Override
	public void deleteById(Long id) {
		donatorRep.deleteById(id);
	}

	@Override
	public void delete(Donator entity) {
		// TODO Auto-generated method stub
		donatorRep.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends Donator> entities) {
		// TODO Auto-generated method stub
		donatorRep.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		donatorRep.deleteAll();
	}

	@Override
	public <S extends Donator> Optional<S> findOne(Example<S> example) {
		return donatorRep.findOne(example);
	}

	@Override
	public <S extends Donator> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return donatorRep.findAll(example, pageable);
	}

	@Override
	public <S extends Donator> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return donatorRep.count(example);
	}

	@Override
	public <S extends Donator> boolean exists(Example<S> example) {
		return donatorRep.exists(example);
	}



}