package com.service.abbasiWelfare;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.persistence.Repo.abbasiWelfare.DonatorRepository;
import com.persistence.model.abbasiWelfare.Donator;

@Service
@Transactional
public class DonatorService implements IDonatorService {

	@Autowired
	DonatorRepository donatorRepository;

	
	@Override
	public Donator addDonator(Donator donator) throws Exception{
		return donatorRepository.save(donator);		
	}

}