package com.service.business;

import java.io.FileNotFoundException;
import java.util.List;

import com.persistence.Repo.business.SellRepo;
import com.persistence.model.business.Sell;

public interface ISellService extends SellRepo{

	String createReport(List<Sell> objs) throws FileNotFoundException;

}
