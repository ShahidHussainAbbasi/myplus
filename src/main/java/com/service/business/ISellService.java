package com.service.business;

import java.io.FileNotFoundException;
import java.util.List;

import com.persistence.Repo.business.SellRepo;
import com.persistence.model.business.Sell;
import com.persistence.model.business.Stock;
import com.web.dto.business.SellDTO;

public interface ISellService extends SellRepo{

	String createReport(List<Sell> objs) throws FileNotFoundException;

	Stock updateStock(SellDTO dto);

	List<Sell> addSell(List<SellDTO> dtos);

}
