package com.myplus.welfare.util;

import java.util.Collection;

public class GenericResponse {
    private String message;
    private String error;
    private String status = "SUCCESS";
    private Object object = null;
    private Collection<?> collection = null;

    public GenericResponse() {
    }

    public GenericResponse(final String status, final String message) {
        this.status = status;
        this.message = message;
    }

    public GenericResponse(final String status, final Object object) {
        this.status = status;
        this.object = object;
    }

    public GenericResponse(final String status, final Collection<?> collection) {
        this.status = status;
        this.collection = collection;
    }

    public GenericResponse(final String status, final String message, final Object object) {
        this.status = status;
        this.message = message;
        this.object = object;
    }

    public GenericResponse(final String status, final String message, final Collection<?> collection) {
        this.status = status;
        this.message = message;
        this.collection = collection;
    }

    public String getMessage() { return message; }
    public void setMessage(final String message) { this.message = message; }

    public String getError() { return error; }
    public void setError(final String error) { this.error = error; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Object getObject() { return object; }
    public void setObject(Object object) { this.object = object; }

    public Collection<?> getCollection() { return collection; }
    public void setCollection(Collection<?> collection) { this.collection = collection; }
}
