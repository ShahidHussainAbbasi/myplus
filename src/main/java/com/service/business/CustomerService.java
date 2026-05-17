package com.service.business;

import java.time.LocalDateTime;
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

import com.persistence.Repo.business.CustomerRepo;
import com.persistence.model.Appointment;
import com.persistence.model.User;
import com.persistence.model.business.Customer;
import com.service.IUserService;
import com.web.dto.business.CustomerHistoryDTO;
import com.web.util.AppUtil;
import com.web.util.ObjectMapperUtils;
import com.web.util.RequestUtil;

@Service
@Transactional
public class CustomerService implements ICustomerService{

    @Autowired
    IUserService userService;
    
    @Autowired
    CustomerRepo customerRepo;

    @Autowired
    private AppUtil appUtil;  

	@Autowired
	RequestUtil requestUtil;

	// @Autowired
	// ObjectMapperUtils objectMapperUtils;

	@Override
	public List<Customer> findAll() {
		// TODO Auto-generated method stub
		return customerRepo.findAll();
	}

	@Override
	public List<Customer> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return customerRepo.findAll(sort);
	}

	@Override
	public List<Customer> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return customerRepo.findAllById(ids);
	}

	@Override
	public <S extends Customer> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return customerRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		customerRepo.flush();
	}

	@Override
	public <S extends Customer> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return customerRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Customer> entities) {
		// TODO Auto-generated method stub
		customerRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		customerRepo.deleteAllInBatch();
	}

	@Override
	public Customer getOne(Long id) {
		// TODO Auto-generated method stub
		return customerRepo.getOne(id);
	}

	@Override
	public <S extends Customer> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return customerRepo.findAll(example);
	}

	@Override
	public <S extends Customer> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return customerRepo.findAll(example,sort);
	}

	@Override
	public Page<Customer> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return customerRepo.findAll(pageable);
	}

	@Override
	public <S extends Customer> S save(S entity) {
		// TODO Auto-generated method stub
		return customerRepo.save(entity);
	}

	@Override
	public Optional<Customer> findById(Long id) {
		// TODO Auto-generated method stub
		return customerRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return customerRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return customerRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		customerRepo.deleteById(id);
		
	}

	@Override
	public void delete(Customer entity) {
		// TODO Auto-generated method stub
		customerRepo.delete(entity);
		
	}

	@Override
	public void deleteAll(Iterable<? extends Customer> entities) {
		// TODO Auto-generated method stub
		customerRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		customerRepo.deleteAll();
	}

	@Override
	public <S extends Customer> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return customerRepo.findOne(example);
	}

	@Override
	public <S extends Customer> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return customerRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Customer> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return customerRepo.count(example);
	}

	@Override
	public <S extends Customer> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return customerRepo.exists(example);
	}

	@Override
	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllByIdInBatch'");
	}

	@Override
	public void deleteAllInBatch(Iterable<Customer> entities) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllInBatch'");
	}

	@Override
	public Customer getById(Long id) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'getById'");
	}

	@Override
	public Customer getReferenceById(Long id) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'getReferenceById'");
	}

	@Override
	public <S extends Customer> List<S> saveAllAndFlush(Iterable<S> entities) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'saveAllAndFlush'");
	}

	@Override
	public void deleteAllById(Iterable<? extends Long> ids) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllById'");
	}

	@Override
	public <S extends Customer, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'findBy'");
	}

    @Override
    public Optional<Appointment> isPatientAppointed(Long doctor_id, String date, String mobile) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isPatientAppointed'");
    }

    @Override
    public Optional<Appointment> findByPatient(Long patient_id) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'findByPatient'");
    }

    @Override
    public List<Appointment> findByDoctor(Long doctor_id) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'findByDoctor'");
    }

    @Override
    public Appointment getLastAppointment(Long FK_hospital_id, Long doctor_id, String date) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getLastAppointment'");
    }

	@Override
	public Customer saveUpdateCustomer(CustomerHistoryDTO dto) throws Exception {

		Customer customerObj = dto.getCustomer().getId() != null ? this.getReferenceById(dto.getCustomer().getId()) : new Customer();

		if(appUtil.isEmptyOrNull(customerObj.getId())){

			Example<Customer> example = Example.of(customerObj);

			User user = requestUtil.getCurrentUser();
			customerObj.setUserId(user.getId());
			customerObj.setUserType(user.getUserType());
			if (dto.getCustomer().getContact() != null) {
				customerObj.setContact(dto.getCustomer().getContact());
			}
			if (dto.getCustomer().getName() != null) {
				customerObj.setName(dto.getCustomer().getName());
			}

			customerObj = this.findOne(example).orElse(customerObj);

			// set the due amount 0 if it is negative
			if (dto.getCustomer().getDueAmount() != null && dto.getCustomer().getDueAmount() < 0) {
				dto.getCustomer().setDueAmount(dto.getCustomer().getDueAmount() * -1);
			} else {
				dto.getCustomer().setDueAmount(0.0F);
			}

			if(appUtil.isEmptyOrNull(customerObj.getId())){ 
				customerObj.setDueAmount(customerObj.getDueAmount() == null ? dto.getCustomer().getDueAmount() : customerObj.getDueAmount() +  dto.getCustomer().getDueAmount() );
				// customerObj.setPaidAmount(customerObj.getPaidAmount() == null ? dto.getCustomer().getPaidAmount() : customerObj.getPaidAmount() + dto.getCustomer().getPaidAmount());
				if (dto.getCustomer().getDueDate() != null) {
					customerObj.setDueDate(dto.getCustomer().getDueDate());
				}
			} else {
				customerObj.setDueAmount(customerObj.getDueAmount() == null ? dto.getCustomer().getDueAmount() : customerObj.getDueAmount() + dto.getCustomer().getDueAmount());
				// customerObj.setPaidAmount(customerObj.getPaidAmount() == null ? dto.getCustomer().getPaidAmount() : customerObj.getPaidAmount() + dto.getCustomer().getPaidAmount());
			}

			// customerObj.setName(dto.getCustomer().getName());
		} else {
				customerObj.setDueAmount(customerObj.getDueAmount() == null ? dto.getCustomer().getDueAmount() : customerObj.getDueAmount() + dto.getCustomer().getDueAmount());
				// customerObj.setPaidAmount(customerObj.getPaidAmount() == null ? dto.getCustomer().getPaidAmount() : customerObj.getPaidAmount() + dto.getCustomer().getPaidAmount());
		 }
		if (customerObj.getDueDate() == null && dto.getCustomer().getDueDate() != null) {
			customerObj.setDueDate(dto.getCustomer().getDueDate());
		}
		customerObj.setDated(LocalDateTime.now());
		customerObj.setUpdated(LocalDateTime.now());
		// this.save(customerObj);
		
		return customerObj;
	}

}