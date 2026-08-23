package com.myplus.business_service.repository;

import com.myplus.business_service.entity.Vender;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VenderRepository extends JpaRepository<Vender, Long> {
    Page<Vender> findByUserId(Long userId, Pageable pageable);
    /**
     * Which suppliers represent this brand.
     *
     * <p>Was {@code findByCompanyId}, deriving from the single {@code company} FK. That FK is gone, and the
     * rename is not cosmetic: <b>Spring Data derives this query at STARTUP</b>, so when the property vanished
     * the repository bean failed to build and the whole application context went down — every container test
     * with it. A method nobody calls can still be load-bearing.
     *
     * <p>Kept rather than deleted because the question it answers got MORE useful, not less: a brand can now
     * have several suppliers, which is the reverse of the relationship this slice introduced.
     */
    Page<Vender> findByCompanies_Id(Long companyId, Pageable pageable);
    long countByUserId(Long userId);
}
