package com.service.business;

import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.business.VenderRepo;
import com.persistence.model.business.Vender;
import com.service.IUserService;

@Service
@Transactional
public class VenderService implements IVenderService {

    @Autowired
    private VenderRepo venderRepo;
    
    @Autowired
    IUserService userService;

    public static final String TOKEN_INVALID = "invalidToken";
    public static final String TOKEN_EXPIRED = "expired";
    public static final String TOKEN_VALID = "valid";

    public static String QR_PREFIX = "https://chart.googleapis.com/chart?chs=200x200&chld=M%%7C0&cht=qr&chl=";
    public static String APP_NAME = "SpringRegistration";
	@Override
	public List<Vender> findAll() {
		// TODO Auto-generated method stub
		return venderRepo.findAll();
	}

	@Override
	public List<Vender> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(sort);
	}

	@Override
	public List<Vender> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return venderRepo.findAllById(ids);
	}

	@Override
	public <S extends Vender> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return venderRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		venderRepo.flush();
	}

	@Override
	public <S extends Vender> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return venderRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Vender> entities) {
		// TODO Auto-generated method stub
		venderRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		venderRepo.deleteAllInBatch();
	}

	@Override
	public Vender getOne(Long id) {
		// TODO Auto-generated method stub
		return venderRepo.getOne(id);
	}

	@Override
	public <S extends Vender> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(example);
	}

	@Override
	public <S extends Vender> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(example,sort);
	}

	@Override
	public Page<Vender> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(pageable);
	}

	@Override
	public <S extends Vender> S save(S entity) {
		// TODO Auto-generated method stub
		return venderRepo.save(entity);
	}

	@Override
	public Optional<Vender> findById(Long id) {
		// TODO Auto-generated method stub
		return venderRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return venderRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return venderRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		venderRepo.deleteById(id);
		
	}

	@Override
	public void delete(Vender entity) {
		// TODO Auto-generated method stub
		venderRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Vender> entities) {
		// TODO Auto-generated method stub
		venderRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		venderRepo.deleteAll();
	}

	@Override
	public <S extends Vender> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return venderRepo.findOne(example);
	}

	@Override
	public <S extends Vender> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Vender> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return venderRepo.count(example);
	}

	@Override
	public <S extends Vender> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return venderRepo.exists(example);
	}


}