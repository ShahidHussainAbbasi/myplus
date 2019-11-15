package com.service.business;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.business.SellRepo;
import com.persistence.model.business.Item;
import com.persistence.model.business.Sell;
import com.persistence.model.business.Stock;
import com.service.IUserService;
import com.web.dto.business.SellDTO;
import com.web.util.AppUtil;
import com.web.util.RequestUtil;

@Service
@Transactional
public class SellService implements ISellService {

	ModelMapper modelMapper = new ModelMapper();
	
	@Autowired
	IUserService userService;
	
	@Autowired
	IStockService stockService;

	@Autowired
	SellRepo sellRepo;

	@Autowired
	RequestUtil requestUtil;

	@Autowired
	IItemService itemService;

	@Autowired
	private AppUtil appUtil;

	private XWPFDocument document;

	@Override
	public List<Sell> findAll() {
		// TODO Auto-generated method stub
		return sellRepo.findAll();
	}

	@Override
	public List<Sell> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(sort);
	}

	@Override
	public List<Sell> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return sellRepo.findAllById(ids);
	}

	@Override
	public <S extends Sell> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return sellRepo.saveAll(entities);
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		sellRepo.flush();
	}

	@Override
	public <S extends Sell> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return sellRepo.saveAndFlush(entity);
	}

	@Override
	public void deleteInBatch(Iterable<Sell> entities) {
		// TODO Auto-generated method stub
		sellRepo.deleteInBatch(entities);
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		sellRepo.deleteAllInBatch();
	}

	@Override
	public Sell getOne(Long id) {
		// TODO Auto-generated method stub
		return sellRepo.getOne(id);
	}

	@Override
	public <S extends Sell> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(example);
	}

	@Override
	public <S extends Sell> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(example, sort);
	}

	@Override
	public Page<Sell> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(pageable);
	}

	@Override
	public <S extends Sell> S save(S entity) {
		// TODO Auto-generated method stub
		return sellRepo.save(entity);
	}

	@Override
	public Optional<Sell> findById(Long id) {
		// TODO Auto-generated method stub
		return sellRepo.findById(id);
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return sellRepo.existsById(id);
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return sellRepo.count();
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		sellRepo.deleteById(id);

	}

	@Override
	public void delete(Sell entity) {
		// TODO Auto-generated method stub
		sellRepo.delete(entity);

	}

	@Override
	public void deleteAll(Iterable<? extends Sell> entities) {
		// TODO Auto-generated method stub
		sellRepo.deleteAll(entities);
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		sellRepo.deleteAll();
	}

	@Override
	public <S extends Sell> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return sellRepo.findOne(example);
	}

	@Override
	public <S extends Sell> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return sellRepo.findAll(example, pageable);
	}

	@Override
	public <S extends Sell> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return sellRepo.count(example);
	}

	@Override
	public <S extends Sell> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return sellRepo.exists(example);
	}

	@Override
	public List<Sell> findSellByStartDate(LocalDateTime sd, Long userId) {
		// TODO Auto-generated method stub
		return sellRepo.findSellByStartDate(sd, userId);
	}

	@Override
	public List<Sell> findSellByEndDate(LocalDateTime ed, Long userId) {
		// TODO Auto-generated method stub
		return sellRepo.findSellByEndDate(ed, userId);
	}

	@Override
	public List<Sell> findSellByDates(LocalDateTime sd, LocalDateTime ed, Long userId) {
		// TODO Auto-generated method stub
		return sellRepo.findSellByDates(sd, ed, userId);
	}

	@Override
	@Transactional
	public List<Sell> addSell(List<SellDTO> dtos) {
		List<Sell> objs = new ArrayList<>();
		if(!appUtil.isEmptyOrNull(dtos)) {
			dtos.forEach(dto ->{
				Stock stock = stockService.updateStock(dto);
				if(!appUtil.isEmptyOrNull(stock)) {
					modelMapper.addConverter(appUtil.stringToLocalDateTime);
					modelMapper.addConverter(appUtil.stringToLocalDate);
					Sell obj = modelMapper.map(dto, Sell.class);
			//		Stock stock = modelMapper.map(dto.getStockDTO(), Stock.class);
					obj.setStock(stock);
					obj.setUserId(requestUtil.getCurrentUser().getId());
					stockService.save(stock);
					objs.add(this.save(obj));
				}
			});
		}
		return objs;
	}
	
	@Override
	public String createReport(List<Sell> objs) throws FileNotFoundException {
		document = new XWPFDocument();
		DecimalFormat df = new DecimalFormat("#.##");
		// Write the Document in file system
		FileOutputStream out = null;
		try {
			String customDir = "/reports";
			String path = requestUtil.getPath(customDir);
//			String path = System.getProperty("user.home") + File.separator + "Documents";
//			path += File.separator + "sellReport";
			File file = new File(path + "createdocument.docx");
			if (file.exists() && file.delete()) {
				System.out.println("File deleted successfully");
			} else {
				System.out.println("Failed to delete the file");
			}
			out = new FileOutputStream(new File(path + "createdocument.docx"));

			// create Paragraph
			XWPFParagraph paragraph = document.createParagraph();
			paragraph.setAlignment(ParagraphAlignment.CENTER);
			XWPFRun run = paragraph.createRun();
			run.setFontSize(14);
//			run.addTab();
//			run.addTab();
//			run.addTab();
//			run.addTab();
//			run.addTab();
//			run.addTab();
			run.setText("Haider Garments");
			run.setBold(true);
			run = paragraph.createRun();
//			run.setFontSize(7);
			run.addCarriageReturn();
//			run.addTab();
//			run.addTab();
//			run.addTab();
//			run.addTab();
//			run.addTab();
			run.setText("Link Road Model Town A Khan Pur");
			run.addCarriageReturn();
//			run.addTab();
//			run.addTab();
//			run.addTab();
//			run.addTab();
//			run.addTab();
//			run.addTab();
			run.setText("03053939495");
			run.setFontSize(8);
			run.addCarriageReturn();
			run.addCarriageReturn();
//			run.addTab();
//			run.addTab();
//			run.addTab();
//			run.addTab();
//			run.addTab();
			run = paragraph.createRun();
			run.setText("Date & Time : "+appUtil.todayDateTimeStr());
			run.setFontSize(7);
//			run.addCarriageReturn();
			XWPFTable table = document.createTable();
			table.setTableAlignment(TableRowAlign.CENTER);
			XWPFTableRow row = table.getRow(0);
			run = row.getCell(0).addParagraph().createRun();
//			run.setFontSize(10);
//			run.setBold(true);
			run.setText("Item");
			run = row.addNewTableCell().addParagraph().createRun();
//			run.setFontSize(10);
//			run.setBold(true);
			run.setText("Qty.");
			run = row.addNewTableCell().addParagraph().createRun();
//			run.setFontSize(10);
//			run.setBold(true);
			run.setText("Total");
			run = row.addNewTableCell().addParagraph().createRun();
//			run.setFontSize(10);
//			run.setBold(true);
			run.setText("Disc.");
			run = row.addNewTableCell().addParagraph().createRun();
//			run.setFontSize(10);
//			run.setBold(true);
			run.setText("Net");
			Float qtys = 0.0F;
			Float prices = 0.0F;
			Float discs = 0.0F;
			Float totalAmount = 0.0F;
			
			for (Sell obj : objs) {
				row = table.createRow();
				float dis = obj.getDiscount() == null ? 0 : obj.getDiscount();
				if (obj.getDt().equals("%")) {
					dis = obj.getTotalAmount() * dis / 100;
				}
				Item item = itemService.getOne(obj.getItemId());
				row.getCell(0).setWidth("1200");
				row.getCell(0).setText(" "+item.getIname());
				row.getCell(1).setWidth("300");
				row.getCell(1).setText(" "+obj.getQuantity());
				row.getCell(2).setWidth("300");
				row.getCell(2).setText(" "+obj.getSellRate() + "");
				row.getCell(3).setWidth("300");
				row.getCell(3).setText(" "+dis + "");
				row.getCell(4).setWidth("300");
				row.getCell(4).setText(" "+obj.getTotalAmount() + "");
					qtys+=obj.getQuantity();
					prices+=obj.getSellRate();
					discs+=dis;
					totalAmount+=obj.getTotalAmount();
			}
			totalAmount = Float.valueOf(df.format(totalAmount));
			prices = Float.valueOf(df.format(prices));
			discs = Float.valueOf(df.format(discs));
			row = table.createRow();
			row.getCell(0).setText("Totals");
//			run = row.getCell(0).addParagraph().createRun();
////			run.setFontSize(14);
//			run.setBold(true);
//			run.setText("Totals");
//			run = row.getCell(1).addParagraph().createRun();
////			run.setFontSize(14);
//			run.setBold(true);
//			run.setText(qtys+"");
			row.getCell(1).setText(" "+qtys);
//			run = row.getCell(2).addParagraph().createRun();
////			run.setFontSize(14);
//			run.setBold(true);
//			run.setText(prices+"");
			row.getCell(2).setText(" "+prices);
//			run = row.getCell(3).addParagraph().createRun();
////			run.setFontSize(14);
//			run.setBold(true);
//			run.setText(discs+"");
			row.getCell(3).setText(" "+discs);
//			run = row.getCell(4).addParagraph().createRun();
////			run.setFontSize(14);
//			run.setBold(true);
//			run.setText(tatalAmount+"");
			row.getCell(4).setText(" "+totalAmount);


			XWPFParagraph newParagraph = document.createParagraph();
			newParagraph.setAlignment(ParagraphAlignment.CENTER);
			run = newParagraph.createRun(); 
			run.addCarriageReturn();
			run.setFontSize(10);
			run.setBold(true);
			run.setText("Totals     : "+ totalAmount);
			run.addCarriageReturn();			
			run.setText("Dsicount: "+ discs);
			run.addCarriageReturn();			
			run.setText("Net         : "+ (totalAmount - discs));
			
			newParagraph = document.createParagraph();
			newParagraph.setAlignment(ParagraphAlignment.CENTER);
			run = newParagraph.createRun(); 
			run.addCarriageReturn();
			run.setFontSize(7);
			run.setBold(true);
			run.setText("Note:Please bring receipt for exchange of goods");
//			run.setText("سامان کی تبدیلی کے لئے براہ مہربانی رسید لائیں");
			run = newParagraph.createRun(); 
			run.addCarriageReturn();
			run.setFontSize(12);
			run.setText("Thank you for shopping with us.");
			run = newParagraph.createRun(); 
			run.addCarriageReturn();
			run.setFontSize(8);
			run.setBold(true);
//			run.setText("For any kind of business and software solutions please contact us on 03114499660 or maxtheservice@gmail.com ");
			run.addCarriageReturn();
//			run.setText("کسی  بھی  قسم  کے  کاروبار اور سافٹ ویئر  کے  حل  کے  لئے  برائے مہربانی  ہم  سے  رابطہ  کریں ");
			run.addCarriageReturn();
			run.setText("Email:maxtheservice@gmail.com");
			run.addCarriageReturn();
			run.setText("Mobile:03114499660");
			run.addCarriageReturn();
//			run.setFontSize(14);
			run.setBold(true);
			run.setText("Web:https://maxtheservice.com/login");
			document.write(out);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			try {
				out.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		System.out.println("createdocument.docx written successully");
		return appUtil.SUCCESS;

	}

	@Override
	public Stock updateStock(SellDTO dto) {
		// TODO Auto-generated method stub
		return null;
	}

}