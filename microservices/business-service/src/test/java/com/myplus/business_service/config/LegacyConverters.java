package com.myplus.business_service.config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.modelmapper.Converter;
import org.modelmapper.spi.MappingContext;

import com.myplus.common.security.time.TenantClock;
import com.myplus.business_service.util.AppUtil;

/**
 * MS-6 — the ModelMapper date converters AppUtil carried until 4 Oct 2026, FROZEN here VERBATIM as part of the oracle
 * the MapStruct mappers are characterized against (MapperProfiles, MapStructOracleTest). Production no longer uses
 * ModelMapper; these exist only so "what the old code did" stays executable. Do not edit them to match new behaviour.
 */
public class LegacyConverters {

    private final AppUtil appUtil = new AppUtil();
    final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    final DateTimeFormatter dateformatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    // The SAME overloads AppUtil offers, so Java picks the same one the original converters did: a String source binds to
    // the blank-aware String overload, everything else to the null check. (One Object overload made "" non-empty here
    // and the oracle threw on blank dates — caught by MapStructOracleTest's "the oracle really maps" check.)
    private boolean isEmptyOrNull(String s) { return appUtil.isEmptyOrNull(s); }
    private boolean isEmptyOrNull(Object o) { return appUtil.isEmptyOrNull(o); }

    public Converter<String, LocalDate> stringToLocalDate = new Converter<String, LocalDate>() {

    	@Override
    	public LocalDate convert(MappingContext<String, LocalDate> arg0) {
			if(isEmptyOrNull(arg0.getSource())) {
		        return TenantClock.today();
			}
	    	return LocalDate.parse(arg0.getSource().toString(), dateformatter);//dateformatter.parse(arg0.getSource().toString());
    	}
	};    
    
    public Converter<String, LocalDate> stringToLocalDateIgnoreEmptyOrNull = new Converter<String, LocalDate>() {

    	@Override
    	public LocalDate convert(MappingContext<String, LocalDate> arg0) {
			if(isEmptyOrNull(arg0.getSource())) {
		        return null;
			}
	    	return LocalDate.parse(arg0.getSource().toString(), dateformatter);//dateformatter.parse(arg0.getSource().toString());
    	}
	};    

	public Converter<LocalDate,String> localDateToString = new Converter<LocalDate,String>() {
    	@Override
    	public String convert(MappingContext<LocalDate,String> arg0) {
    		return isEmptyOrNull(arg0.getSource())? TenantClock.today().format(dateformatter) : dateformatter.format(arg0.getSource());
    	}
	};    

	public Converter<LocalDate,String> localDateToStringIgnoreEmptyOrNull = new Converter<LocalDate,String>() {
    	@Override
    	public String convert(MappingContext<LocalDate,String> arg0) {
    		return isEmptyOrNull(arg0.getSource())? null : dateformatter.format(arg0.getSource());
    	}
	};    

	public Converter<String, LocalDateTime> stringToLocalDateTime = new Converter<String, LocalDateTime>() {
    	@Override
    	public LocalDateTime convert(MappingContext<String, LocalDateTime> arg0) {
			if(isEmptyOrNull(arg0.getSource())) {
		        return LocalDateTime.now();
			}
	    	return parseFlexibleDateTime(arg0.getSource().toString());
    	}
	};

	public Converter<String, LocalDateTime> stringToLocalDateTimeIgnoreEmptyOrNull = new Converter<String, LocalDateTime>() {
    	@Override
    	public LocalDateTime convert(MappingContext<String, LocalDateTime> arg0) {
			if(isEmptyOrNull(arg0.getSource())) {
		        return null;
			}
	    	return parseFlexibleDateTime(arg0.getSource().toString());
    	}
	};

	// Parse a String into LocalDateTime, accepting both "dd-MM-yyyy HH:mm:ss" and
	// date-only "dd-MM-yyyy" (date-only falls back to start of day).
	private LocalDateTime parseFlexibleDateTime(String s) {
		// TZ-1: a time (or a date's midnight) typed in the business's zone → the UTC the services work in.
		try {
			return com.myplus.common.security.time.RenderZone.fromDisplay(LocalDateTime.parse(s, dateTimeFormatter));
		} catch (DateTimeParseException e) {
			return com.myplus.common.security.time.RenderZone.fromDisplay(LocalDate.parse(s, dateformatter).atStartOfDay());
		}
	}

	public Converter<LocalDateTime,String> localDateTimeToString = new Converter<LocalDateTime,String>() {
    	@Override
    	public String convert(MappingContext<LocalDateTime,String> arg0) {
    		return isEmptyOrNull(arg0.getSource()) ? com.myplus.common.security.time.RenderZone.toDisplay(LocalDateTime.now()).format(dateTimeFormatter) : dateTimeFormatter.format(com.myplus.common.security.time.RenderZone.toDisplay(arg0.getSource()));   // TZ-1//arg0.getSource().toUppercase();
    	}
	}; 

	public Converter<LocalDateTime,String> localDateTimeToStringIgnoreEmptyOrNull = new Converter<LocalDateTime,String>() {
    	@Override
    	public String convert(MappingContext<LocalDateTime,String> arg0) {
    		return isEmptyOrNull(arg0.getSource()) ? null : dateTimeFormatter.format(com.myplus.common.security.time.RenderZone.toDisplay(arg0.getSource()));   // TZ-1//arg0.getSource().toUppercase();
    	}
	};
}
