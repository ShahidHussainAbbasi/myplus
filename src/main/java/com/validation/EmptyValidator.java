package com.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class EmptyValidator implements ConstraintValidator<ValidateEmpty, Object> {

	@Override
    public void initialize(final ValidateEmpty constraintAnnotation) {
    }

    @Override
    public boolean isValid(final Object instance, final ConstraintValidatorContext context) {
    	if(instance instanceof Long)
		    if(instance==null || ((Long)instance)<=0)
		    	return false;
		    else
		    	return true;
    	else if(instance instanceof Integer)
		    if(instance==null || ((Integer)instance)<=0)
		    	return false;
		    else
		    	return true;
    	else if(instance instanceof Float)
		    if(instance==null || ((Float)instance)<=0)
		    	return false;
		    else
		    	return true;
    	else if(instance instanceof Double)
		    if(instance==null || ((Double)instance)<=0)
		    	return false;
		    else
		    	return true;
    	else if(instance instanceof String)
		    if(instance==null || ((String)instance).length()==0)
		    	return false;
		    else
		    	return true;
	    else
	    	return false;
    }

}
