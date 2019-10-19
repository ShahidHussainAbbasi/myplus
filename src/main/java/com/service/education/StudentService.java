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

@Service
@Transactional
public class StudentService implements IStudentService {


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

	@Override
	public List<Student> findStudentsByStudentIdsAndUserId(Long userId,List<Long> studentIds,String status){
		return studentRepo.findStudentsByStudentIdsAndUserId(userId, studentIds, status);
	}

	@Override
	public List<Student> findStudentsByGuardianIdsAndUserId(Long userId,List<Long> guardianIds,String status){
		return studentRepo.findStudentsByGuardianIdsAndUserId(userId, guardianIds, status);
	}

	@Override
	public List<Student> findStudentsByGradeIdsAndUserId(Long userId,List<Long> gradeIds,String status){
		return studentRepo.findStudentsByGradeIdsAndUserId(userId, gradeIds, status);
	}

	@Override
	public List<Student> findStudentsByCampusIdsAndUserId(Long userId,List<Long> campusIds,String status){
		return studentRepo.findStudentsByCampusIdsAndUserId(userId, campusIds, status);
	}

//	@Override
//	public List<Student> findByStudentIdsAndUserIdCriteria(Long userId,List<Long> studentIds){
//        return studentRepo.findAll(new Specification<Student>() {
//            /**
//			 * 
//			 */
//			private static final long serialVersionUID = 1L;
//
//			@Override
//            public Predicate toPredicate(Root<Student> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder) {
//                List<Predicate> predicates = new ArrayList<>();
//                if(studentIds!=null) {
////                    criteriaBuilder.equal((root.get("u"), "%" + text + "%"),
////                    criteriaBuilder.like(root.get("employeeEmail"), "%" + text + "%"))
//
//                    predicates.add(criteriaBuilder.equal(root.get("userId"), userId));
//                    predicates.add(criteriaBuilder.in(root.get("id")).in(studentIds));
//                    }	
////                    );
////                }	
//                return criteriaBuilder.and(predicates.toArray(new Predicate[predicates.size()]));
//            }
//
////			@Override
////			public Predicate toPredicate(Root<Student> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder) {
////				// TODO Auto-generated method stub
////				return null;
////			}
//        });
//	
//    
//}
//
//	@Override
//	public Optional<Student> findOne(Specification<Student> spec) {
//		// TODO Auto-generated method stub
//		return null;
//	}
//
//	@Override
//	public List<Student> findAll(Specification<Student> spec) {
//		// TODO Auto-generated method stub
//		return null;
//	}
//
//	@Override
//	public Page<Student> findAll(Specification<Student> spec, Pageable pageable) {
//		// TODO Auto-generated method stub
//		return null;
//	}
//
//	@Override
//	public List<Student> findAll(Specification<Student> spec, Sort sort) {
//		// TODO Auto-generated method stub
//		return null;
//	}
//
//	@Override
//	public long count(Specification<Student> spec) {
//		// TODO Auto-generated method stub
//		return 0;
//	}
}