package com.web.util;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

//@Component
public class GenericResponse {
    private String message;
    private String error;
    private String status = "SUCCESS";
    private Object object = null;
    private Collection<?> collection = null;
    
    public GenericResponse() {
    }

    public GenericResponse(final String status, final String message) {
        super();
        this.message = message;
        this.status = status;
    }

    public GenericResponse(final String status,final Object object) {
        super();
        this.status = status;
        this.object = object;
    }

    public GenericResponse(final String status,final Collection<?> collection) {
        super();
        this.status = status;
        this.collection = collection;
    }

    public GenericResponse(final String status, final String message,final Object object) {
        super();
        this.message = message;
        this.status = status;
        this.object = object;
    }

    public GenericResponse(final String status, final String message,final Object object,final Collection<?> collection) {
        super();
        this.message = message;
        this.status = status;
        this.object = object;
        this.collection = collection;
    }

    public GenericResponse(final String status, final String message,final Collection collection) {
        super();
        this.message = message;
        this.status = status;
        this.collection = collection;
    }

    public GenericResponse(final String status) {
        super();
        this.status = status;
    }

    public GenericResponse(List<ObjectError> allErrors, String error) {
        this.error = error;
        String temp = allErrors.stream().map(e -> {
            if (e instanceof FieldError) {
                return "{\"field\":\"" + ((FieldError) e).getField() + "\",\"defaultMessage\":\"" + e.getDefaultMessage() + "\"}";
            } else {
                return "{\"object\":\"" + e.getObjectName() + "\",\"defaultMessage\":\"" + e.getDefaultMessage() + "\"}";
            }
        }).collect(Collectors.joining(","));
        this.message = "[" + temp + "]";
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(final String message) {
        this.message = message;
    }

    public String getError() {
        return error;
    }

    public void setError(final String error) {
        this.error = error;
    }

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	/**
	 * @return the object
	 */
	public Object getObject() {
		return object;
	}

	/**
	 * @param object the object to set
	 */
	public void setObject(Object object) {
		this.object = object;
	}

	/**
	 * @return the collection
	 */
	public Collection<?> getCollection() {
		return collection;
	}

	/**
	 * @param collection the collection to set
	 */
	public void setCollection(Collection<?> collection) {
		this.collection = collection;
	}

    
}
