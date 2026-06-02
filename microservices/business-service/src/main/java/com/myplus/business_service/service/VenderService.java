package com.myplus.business_service.service;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import jakarta.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.stereotype.Service;

import com.myplus.business_service.repository.VenderRepo;
import com.myplus.business_service.entity.Vender;


@Service
@Transactional
public class VenderService implements IVenderService {

    @Autowired
    private VenderRepo venderRepo;
    
    @Autowired
    

    public static final String TOKEN_INVALID = "invalidToken";
    public static final String TOKEN_EXPIRED = "expired";
    public static final String TOKEN_VALID = "valid";

    public static String QR_PREFIX = "https://chart.googleapis.com/chart?chs=200x200&chld=M%%7C0&cht=qr&chl=";
    public static String APP_NAME = "SpringRegistration";
	public List<Vender> findAll() {
		// TODO Auto-generated method stub
		return venderRepo.findAll();
	}

	public List<Vender> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(sort);
	}

	public List<Vender> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return venderRepo.findAllById(ids);
	}

	public <S extends Vender> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return venderRepo.saveAll(entities);
	}

	public void flush() {
		// TODO Auto-generated method stub
		venderRepo.flush();
	}

	public <S extends Vender> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return venderRepo.saveAndFlush(entity);
	}

	public void deleteInBatch(Iterable<Vender> entities) {
		// TODO Auto-generated method stub
		venderRepo.deleteInBatch(entities);
	}

	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		venderRepo.deleteAllInBatch();
	}

	public Vender getOne(Long id) {
		// TODO Auto-generated method stub
		return venderRepo.getOne(id);
	}

	public <S extends Vender> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(example);
	}

	public <S extends Vender> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(example,sort);
	}

	public Page<Vender> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(pageable);
	}

	public <S extends Vender> S save(S entity) {
		// TODO Auto-generated method stub
		return venderRepo.save(entity);
	}

	public Optional<Vender> findById(Long id) {
		// TODO Auto-generated method stub
		return venderRepo.findById(id);
	}

	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return venderRepo.existsById(id);
	}

	public long count() {
		// TODO Auto-generated method stub
		return venderRepo.count();
	}

	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		venderRepo.deleteById(id);
		
	}

	public void delete(Vender entity) {
		// TODO Auto-generated method stub
		venderRepo.delete(entity);
		
	}

	public void deleteAll(Iterable<? extends Vender> entities) {
		// TODO Auto-generated method stub
		venderRepo.deleteAll(entities);
	}

	public void deleteAll() {
		// TODO Auto-generated method stub
		venderRepo.deleteAll();
	}

	public <S extends Vender> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return venderRepo.findOne(example);
	}

	public <S extends Vender> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(example, pageable);
	}

	public <S extends Vender> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return venderRepo.count(example);
	}

	public <S extends Vender> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return venderRepo.exists(example);
	}

	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllByIdInBatch'");
	}

	public void deleteAllInBatch(Iterable<Vender> entities) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllInBatch'");
	}

	public Vender getById(Long id) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'getById'");
	}

	public Vender getReferenceById(Long id) {
		// TODO Auto-generated method stub
		return venderRepo.getReferenceById(id);
	}

	public <S extends Vender> List<S> saveAllAndFlush(Iterable<S> entities) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'saveAllAndFlush'");
	}

	public void deleteAllById(Iterable<? extends Long> ids) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllById'");
	}

	public <S extends Vender, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'findBy'");
	}


}