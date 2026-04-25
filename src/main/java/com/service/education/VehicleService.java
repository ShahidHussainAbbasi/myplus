package com.service.education;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.stereotype.Service;

import com.persistence.Repo.education.VehicleRepo;
import com.persistence.model.education.Vehicle;

@Service
@Transactional
public class VehicleService implements IVehicleService {

    @Autowired
    VehicleRepo vehicleRepo;

	@Override
	public List<Vehicle> findAll() {
		// TODO Auto-generated method stub
		return vehicleRepo.findAll();
	}

	@Override
	public List<Vehicle> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return vehicleRepo.findAll(sort);
	}

	@Override
	public List<Vehicle> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return vehicleRepo.findAllById(ids);
	}

	@Override
	public <S extends Vehicle> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return vehicleRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		vehicleRepo.flush();
	}

	@Override
	public <S extends Vehicle> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return vehicleRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Vehicle> entities) {
		// TODO Auto-generated method stub
		vehicleRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		vehicleRepo.deleteAllInBatch();
	}

	@Override
	public Vehicle getOne(Long id) {
		// TODO Auto-generated method stub
		return vehicleRepo.getOne(id);
	}

	@Override
	public <S extends Vehicle> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return vehicleRepo.findAll(example);
	}

	@Override
	public <S extends Vehicle> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return vehicleRepo.findAll(example,sort);
	}

	@Override
	public Page<Vehicle> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return vehicleRepo.findAll(pageable);
	}

	@Override
	public <S extends Vehicle> S save(S entity) {
		// TODO Auto-generated method stub
		return vehicleRepo.save(entity);
	}

	@Override
	public Optional<Vehicle> findById(Long id) {
		// TODO Auto-generated method stub
		return vehicleRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return vehicleRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return vehicleRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		vehicleRepo.deleteById(id);
		
	}

	@Override
	public void delete(Vehicle entity) {
		// TODO Auto-generated method stub
		vehicleRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Vehicle> entities) {
		// TODO Auto-generated method stub
		vehicleRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		vehicleRepo.deleteAll();
	}

	@Override
	public <S extends Vehicle> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return vehicleRepo.findOne(example);
	}

	@Override
	public <S extends Vehicle> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return vehicleRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Vehicle> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return vehicleRepo.count(example);
	}

	@Override
	public <S extends Vehicle> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return vehicleRepo.exists(example);
	}

	@Override
	public int updateStatus(String status, String id) {
		// TODO Auto-generated method stub
		return vehicleRepo.updateStatus(status, id);
	}

	@Override
	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllByIdInBatch'");
	}

	@Override
	public void deleteAllInBatch(Iterable<Vehicle> entities) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllInBatch'");
	}

	@Override
	public Vehicle getById(Long id) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'getById'");
	}

	@Override
	public Vehicle getReferenceById(Long id) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'getReferenceById'");
	}

	@Override
	public <S extends Vehicle> List<S> saveAllAndFlush(Iterable<S> entities) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'saveAllAndFlush'");
	}

	@Override
	public void deleteAllById(Iterable<? extends Long> ids) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllById'");
	}

	@Override
	public <S extends Vehicle, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'findBy'");
	}

}