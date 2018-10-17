/**
 * 
 */
package org.baeldung.web.dto;

import org.baeldung.web.util.GenericResponse;

/**
 * @author sabbasi
 *
 */
public class BaseDOTO {

	GenericResponse genericResponse;

	public GenericResponse getGenericResponse() {
		return genericResponse;
	}

	public void setGenericResponse(GenericResponse genericResponse) {
		this.genericResponse = genericResponse;
	}
	
}
