/**
 * 
 */
package org.baeldung.persistence.dao;

import org.baeldung.persistence.model.Hospital;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * @author sabbasi
 *
 */
public interface HospitalRepository extends JpaRepository<Hospital, Long> {
	
    Hospital findByEmail(String email);
    
    @Override
    void delete(Hospital hospital);
}
