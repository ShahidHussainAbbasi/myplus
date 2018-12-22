package com.validation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class MobileNumberValidator implements ConstraintValidator<ValidMobileNumber, String> {
    private Pattern pattern;
    private Matcher matcher;
//    final String reg = "^((\\+923)|(00923)|(03))-{0,1}\\d{2}\\d{7}$";
    private static final String MOBILE_NUMBER_PATTERN = "^((\\+923)|(00923)|(03))-{0,1}\\d{2}\\d{7}$";

    @Override
    public void initialize(final ValidMobileNumber constraintAnnotation) {
    }

    @Override
    public boolean isValid(final String mobileNo, final ConstraintValidatorContext context) {
        return (validateMobileNumber(mobileNo));
    }

    private boolean validateMobileNumber(final String mobileNo) {
        pattern = Pattern.compile(MOBILE_NUMBER_PATTERN);
        matcher = pattern.matcher(mobileNo.replace(",", ""));
        return matcher.matches();
    }
}
