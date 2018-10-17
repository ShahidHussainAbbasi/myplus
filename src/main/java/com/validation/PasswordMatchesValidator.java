package com.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.web.dto.UserDto;

public class PasswordMatchesValidator implements ConstraintValidator<PasswordMatches, Object> {

    @Override
    public void initialize(final PasswordMatches constraintAnnotation) {
        //
    }

    @Override
    public boolean isValid(final Object obj, final ConstraintValidatorContext context) {
    	if(obj instanceof UserDto) {
    		final UserDto user = (UserDto) obj;
        	return user.getPassword().equals(user.getMatchingPassword());
    	}else {
    		return true;
    	}
    }

}
