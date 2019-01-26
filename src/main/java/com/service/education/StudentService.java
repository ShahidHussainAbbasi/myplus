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

import com.persistence.Repo.education.StudentRepo;
import com.persistence.model.education.Student;
import com.service.UserService;

@Service
@Transactional
public class StudentService implements IStudentService {


    @Autowired
    UserService userService;
    
    @Autowired
    StudentRepo studentRepo;
    
	@Override
	public List<Student> findAll() {
		// TODO Auto-generated method stub
		return studentRepo.findAll();
	}

	@Override
	public List<Student> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return studentRepo.findAll(sort);
	}

	@Override
	public List<Student> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return studentRepo.findAllById(ids);
	}

	@Override
	public <S extends Student> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return studentRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		studentRepo.flush();
	}

	@Override
	public <S extends Student> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return studentRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Student> entities) {
		// TODO Auto-generated method stub
		studentRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		studentRepo.deleteAllInBatch();
	}

	@Override
	public Student getOne(Long id) {
		// TODO Auto-generated method stub
		return studentRepo.getOne(id);
	}

	@Override
	public <S extends Student> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return studentRepo.findAll(example);
	}

	@Override
	public <S extends Student> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return studentRepo.findAll(example, sort);
	}

	@Override
	public Page<Student> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return studentRepo.findAll(pageable);
	}

	@Override
	public <S extends Student> S save(S entity) {
		// TODO Auto-generated method stub
		return studentRepo.save(entity);
	}

	@Override
	public Optional<Student> findById(Long id) {
		// TODO Auto-generated method stub
		return studentRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return studentRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return studentRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		studentRepo.deleteById(id);
	}

	@Override
	public void delete(Student entity) {
		// TODO Auto-generated method stub
		studentRepo.delete(entity);
	}

	@Override
	public void deleteAll(Iterable<? extends Student> entities) {
		// TODO Auto-generated method stub
		studentRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		studentRepo.deleteAll();
	}

	@Override
	public <S extends Student> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return studentRepo.findOne(example);
	}

	@Override
	public <S extends Student> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return studentRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Student> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return studentRepo.count(example);
	}

	@Override
	public <S extends Student> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return studentRepo.exists(example);
	}

	@Override
	public int updateStatus(String status, String id) {
		// TODO Auto-generated method stub
		return updateStatus(status, id);
	}

    
}