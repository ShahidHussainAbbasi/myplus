package com.myplus.business_service.util;

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
        this.status = status;
        this.message = message;
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

    public GenericResponse(final Object object,final Collection<?> collection) {
        super();
        this.object = object;
        this.collection = collection;
    }
    
    public GenericResponse(final String status, final String message,final Object object) {
        super();
        this.status = status;
        this.message = message;
        this.object = object;
    }

    public GenericResponse(final String status, final String message,final Object object,final Collection<?> collection) {
        super();
        this.status = status;
        this.message = message;
        this.object = object;
        this.collection = collection;
    }

    public GenericResponse(final String status, final String message,final Collection<?> collection) {
        super();
        this.status = status;
        this.message = message;
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

    

    /**
     * {@code true} when {@link #getStatus()} is SUCCESS. <b>Derived, never stored</b> — there is exactly one
     * source of truth for whether the call worked, and this is a second way to read it, not a second answer.
     *
     * <h3>Why this exists</h3>
     *
     * This codebase answers in two envelopes: {@code ApiResponse} carries {@code success}, and this class
     * carries {@code status}. Every caller therefore has to know which endpoint it is talking to, and the
     * failure when it guesses wrong is SILENT in the worst possible direction:
     *
     * <pre>
     *   expect(body.success).to.eq(true)       on a GenericResponse -> fails on a perfectly good call
     *   expect(body.success).to.not.eq(true)   on a GenericResponse -> PASSES FOR EVERY OUTCOME
     * </pre>
     *
     * <p>The second line is the dangerous one: an assertion against a field that does not exist can never
     * fail, so three refusal tests reported green while asserting nothing at all — they would have passed had
     * the server happily done the thing they existed to forbid.
     *
     * <p>Converging the two envelopes properly means touching ~136 controllers and ~114 JS call sites, which
     * is its own slice. This is the bridge: strictly additive, true by construction, and it makes
     * {@code body.success} mean the same thing on every endpoint in the platform.
     */
    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(this.status);
    }
}
