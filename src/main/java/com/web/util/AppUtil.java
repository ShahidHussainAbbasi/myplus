/**
 * 
 */
package com.web.util;

import java.io.IOException;
import java.lang.reflect.Type;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.persistence.model.Geolocation;
import com.service.IGeoLocationService;

import lombok.extern.slf4j.Slf4j;

/**
 * @author sabbasi
 *
 */
@Slf4j
@Component
public class AppUtil {
	
	private final static Logger LOGGER = LoggerFactory.getLogger(AppUtil.class);
	
	@Autowired
	IGeoLocationService geoLocationService;

    private static final String EMAIL_PATTERN = "^[_A-Za-z0-9-\\+]+(\\.[_A-Za-z0-9-]+)*@" + "[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$";
    private static final String MOBILE_NUMBER_PATTERN = "^((\\+923)|(00923)|(03))-{0,1}\\d{2}\\d{7}$";

	final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    final DateTimeFormatter dateformatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    final SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
    public Map<String,String> countryMap=null;
    // Evaluate page size. If requested parameter is null, return initial
    final int INITIAL_PAGE = 0;
    // page size
    final int INITIAL_PAGE_SIZE = 10;

	public final String ACTIVE = "Active";
	public final String INACTIVE = "Inactive";
	public final String SUCCESS = "SUCCESS";
	public final String FAILURE = "FAILURE";
	public final String FOUND = "FOUND";
	public final String NOT_FOUND = "NOT_FOUND";
	public final String ERROR = "ERROR";

    @NonNull
    public String toJson(@org.springframework.lang.Nullable Object object) {
        try {
            return object == null ? "null" : OBJECT_MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public <T> T fromJson(@NonNull String json, @NonNull Type type) {
        JavaType javaType = OBJECT_MAPPER.constructType(type);
        try {
            return OBJECT_MAPPER.readValue(json, javaType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
   	//Get current date time
    public String todayDateStr() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(dateformatter);
    }

   	//Get current date
    public String getDateStr(LocalDateTime date) {
    	if(date==null)
    		return "";
    	return dateformatter.format(date);
    }

   	//Get current date
    public String getLocalDateStr() {
    	return dateformatter.format(LocalDate.now());
    }

    //Get current date time
    public String getLoaclDateStr(LocalDate date) {
    	if(date==null)
    		return "";
    	return dateformatter.format(date);
    }

    //Get current date time
    public String getLocalDateStr(LocalDate date) {
    	if(date==null)
    		return "";
    	return dateformatter.format(date);
    }

    //Get current date time
    public String getLocalDateTimeStr(LocalDateTime date) {
    	if(date==null)
    		return "";
    	return dateTimeFormatter.format(date);
    }

    //Get current date time
    public Date getDate(String date) throws ParseException {
    	if(StringUtils.isEmpty(date))
    		return new Date();
    	return dateFormat.parse(date);
    }

   	//Get Local date time
    public LocalDateTime getDateTime(String dateTimeStr) throws ParseException {
    	if(StringUtils.isEmpty(dateTimeStr))
    		return LocalDateTime.now();
    	LocalDateTime dateTime = LocalDateTime.parse(dateTimeStr, dateTimeFormatter);
    	return dateTime;
    }
   	//Get current date
    public LocalDate getLocalDate(String dateStr) throws ParseException {
    	if(StringUtils.isEmpty(dateStr))
    		return LocalDate.now();
    	LocalDate dateTime = LocalDate.parse(dateStr, dateformatter);
    	return dateTime;
    }
   	//Get date time str
    public String getDateTimeStr(LocalDateTime dateTime){
    	if(StringUtils.isEmpty(dateTime))
    		return "";
        return dateTimeFormatter.format(dateTime);
    }

    //Get current date time
    public String todayDateTimeStr() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(dateTimeFormatter);
    }

    @PostConstruct
    void init() {
    	try {
			List<Geolocation> countries =  geoLocationService.loadCountries();
			if(countries==null)
				return;
			LOGGER.info("Contries fetched "+countries.size());
			countryMap = new HashMap<String, String>();
			countries.forEach(c ->{
				countryMap.put(c.getCountryCode(),c.getCountry());
			});
			LOGGER.info("Contries availabe in map  "+countryMap.size());
    	}catch(Exception e) {
    		e.printStackTrace();
    		LOGGER.info("Contries fetching issues  "+this.getClass().getName()+" : "+e.getCause());
    	}
    }

  
	public boolean isEmptyOrNull( Collection<?> collection ){
		if( collection == null || collection.isEmpty() ){
			return true;
		}
		return false;
	}

	/**
	 * This method returns true of the map is null or is empty.
	 * @param map
	 * @return true | false
	 */
	public boolean isEmptyOrNull( Map<?, ?> map ){
		if( map == null || map.isEmpty() ){
			return true;
		}
		return false;
	}

	/**
	 * This method returns true if the objet is null.
	 * @param object
	 * @return true | false
	 */
	public boolean isEmptyOrNull( Object object ){
		if( object == null ){
			return true;
		}
		return false;
	}

	/**
	 * This method returns true if the input array is null or its length is zero.
	 * @param array
	 * @return true | false
	 */
	public boolean isEmptyOrNull( Object[] array ){
		if( array == null || array.length == 0 ){
			return true;
		}
		return false;
	}

	/**
	 * This method returns true if the input string is null or its length is zero.
	 * @param string
	 * @return true | false
	 */
	public boolean isEmptyOrNull( String string ){
		if( string == null || string.equals("null") || string.trim().length() == 0 ){
			return true;
		}
		return false;
	}    
   
	public Sort orderByASC(String f) {
		// TODO Auto-generated method stub
//		return new Sort(Sort.Direction.ASC, f);
		return Sort.by(f).ascending();
	}

	public Sort orderByDESC(String f) {
		// TODO Auto-generated method stub
		return new Sort(Sort.Direction.DESC, f);
//		return Sort.by(f).descending();
	}

	public Sort orderByASC(String f1, String f2) {
		// TODO Auto-generated method stub
		return new Sort(Sort.Direction.ASC, f1).and(new Sort(Sort.Direction.ASC,f2));
	}

	public Sort orderByDESC(String f1, String f2) {
		// TODO Auto-generated method stub
		return new Sort(Sort.Direction.DESC, f1).and(new Sort(Sort.Direction.DESC,f2));
	}

	public Sort orderByASCDESC(String f1, String f2) {
		// TODO Auto-generated method stub
		return new Sort(Sort.Direction.ASC, f1).and(new Sort(Sort.Direction.DESC,f2));
	}

	public Sort orderByDESCASC(String f1, String f2) {
		// TODO Auto-generated method stub
		return new Sort(Sort.Direction.DESC, f1).and(new Sort(Sort.Direction.ASC,f2));
	}
	
	public Sort createDynamicSort(String[] arrayOrdre) {
        return  Sort.by(arrayOrdre);
    }	

	public PageRequest getPageRequest(int INITIAL_PAGE, int INITIAL_PAGE_SIZE, Sort sort) {
        return PageRequest.of(INITIAL_PAGE, INITIAL_PAGE_SIZE,sort);
    }
    
    public PageRequest getPageRequest(Sort sort) {
        return getPageRequest(INITIAL_PAGE, INITIAL_PAGE_SIZE, sort);
    }

    public void le(Class<?> c,Exception e) {
		log.error(c.getName()+"  >>>  "+e.getMessage());
    }
    public void li(Class<?> c,String s) {
		log.info(c.getName()+"  >>>  "+s);
    }
    public void lw(Class<?> c,String s) {
		log.warn(c.getName()+"  >>>  "+s);
    }
    
    public LocalDateTime dateTimeByDay(int day) {
    	return LocalDateTime.now().withDayOfMonth(day);
//    	return (LocalDateTime) dateTimeFormatter.parse(dateTimeFormatter.format(LocalDateTime.now().withDayOfMonth(day)));
    }
    
    public LocalDateTime firstDateTimeOfMonth() {
    	return LocalDateTime.now().withDayOfMonth(1);
    }
    
    public LocalDateTime lastDateTimeOfMonth() {
    	Calendar calendar = Calendar.getInstance();
    	int LAST_DAY_OF_MONTH = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
    	return LocalDateTime.now().withDayOfMonth(LAST_DAY_OF_MONTH);
    }

    public LocalDate firstDateOfMonth() {
    	return LocalDate.now().withDayOfMonth(1);
    }
    
    public LocalDate lastDateOfMonth() {
    	return LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
    }

//    for future use
//    <dependency>
//    <groupId>com.fasterxml.jackson.dataformat</groupId>
//    <artifactId>jackson-dataformat-csv</artifactId>
//    </dependency>
//    private static final CsvMapper mapper = new CsvMapper();
//    public static <T> List<T> read(Class<T> clazz, InputStream stream) throws IOException {
//        CsvSchema schema = mapper.schemaFor(clazz).withHeader().withColumnReordering(true);
//        ObjectReader reader = mapper.readerFor(clazz).with(schema);
//        return reader.<T>readValues(stream).readAll();
//    }
    
    public boolean validateEmail(final String email) {
    	Pattern pattern = Pattern.compile(EMAIL_PATTERN);
    	Matcher matcher = pattern.matcher(email);
        return matcher.matches();
    }
    public boolean validateMobileNumber(final String mobileNo) {
    	Pattern pattern = Pattern.compile(MOBILE_NUMBER_PATTERN);
    	Matcher matcher = pattern.matcher(mobileNo);
        return matcher.matches();
    }
    
}
