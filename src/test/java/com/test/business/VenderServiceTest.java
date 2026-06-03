package com.test.business;

import com.persistence.Repo.business.VenderRepo;
import com.persistence.model.Company;
import com.persistence.model.business.Vender;
import com.service.business.VenderService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Example;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VenderServiceTest {

    @Mock
    private VenderRepo venderRepo;

    @InjectMocks
    private VenderService venderService;

    private Vender vender;
    private Company company;

    @BeforeEach
    void setUp() {
        company = new Company();
        company.setId(1L);
        company.setName("Test Corp");
        company.setUserId(10L);

        vender = new Vender();
        vender.setId(1L);
        vender.setUserId(10L);
        vender.setUserType("BUSINESS");
        vender.setName("Ali Traders");
        vender.setMobile("03001234567");
        vender.setEmail("ali@traders.com");
        vender.setAddress("Lahore");
        vender.setCompany(company);
        vender.setDated(LocalDateTime.now());
        vender.setUpdated(LocalDateTime.now());
    }

    // ─── ADD (new) ────────────────────────────────────────────────────────────

    @Test
    void addVender_newEntity_savesAndReturnsWithId() {
        Vender newVender = new Vender();
        newVender.setUserId(10L);
        newVender.setName("New Trader");
        newVender.setCompany(company);

        Vender saved = new Vender();
        saved.setId(2L);
        saved.setName("New Trader");
        saved.setCompany(company);
        when(venderRepo.save(newVender)).thenReturn(saved);

        Vender result = venderService.save(newVender);

        assertNotNull(result.getId());
        assertEquals("New Trader", result.getName());
        verify(venderRepo).save(newVender);
    }

    @Test
    void addVender_duplicateCheck_detectsExistingVender() {
        Vender filter = new Vender();
        filter.setUserId(10L);
        filter.setName("Ali Traders");
        Example<Vender> example = Example.of(filter);
        when(venderRepo.exists(example)).thenReturn(true);

        boolean exists = venderService.exists(example);

        assertTrue(exists, "Duplicate vender by name and userId should be detected");
    }

    @Test
    void addVender_noCompanyId_throwsException() {
        // VenderController sets company via companyService.getReferenceById(dto.getCompanyId())
        // If companyId is null, this call fails — test that vender requires a company
        Vender noCompanyVender = new Vender();
        noCompanyVender.setUserId(10L);
        noCompanyVender.setName("No Company Vender");
        noCompanyVender.setCompany(null);

        when(venderRepo.save(noCompanyVender)).thenThrow(new jakarta.validation.ConstraintViolationException(
                "company_id cannot be null", null));

        assertThrows(jakarta.validation.ConstraintViolationException.class,
                () -> venderService.save(noCompanyVender));
    }

    // ─── UPDATE ───────────────────────────────────────────────────────────────

    @Test
    void updateVender_existingEntity_preservesDatedField() {
        LocalDateTime originalDated = LocalDateTime.of(2024, 6, 1, 12, 0);
        vender.setDated(originalDated);
        vender.setUpdated(LocalDateTime.now());

        when(venderRepo.save(vender)).thenReturn(vender);

        Vender result = venderService.save(vender);

        assertEquals(originalDated, result.getDated(), "Dated must not change on update");
        verify(venderRepo).save(vender);
    }

    @Test
    void updateVender_getReferenceById_returnsEntity() {
        when(venderRepo.getReferenceById(1L)).thenReturn(vender);

        Vender ref = venderService.getReferenceById(1L);

        assertEquals(1L, ref.getId());
        assertEquals("Ali Traders", ref.getName());
    }

    @Test
    void updateVender_companySwitched_savesNewCompanyReference() {
        Company newCompany = new Company();
        newCompany.setId(2L);
        newCompany.setName("New Corp");

        vender.setCompany(newCompany);
        when(venderRepo.save(vender)).thenReturn(vender);

        Vender result = venderService.save(vender);

        assertEquals(2L, result.getCompany().getId());
    }

    // ─── DELETE ───────────────────────────────────────────────────────────────

    @Test
    void deleteVender_byId_delegatesToRepo() {
        doNothing().when(venderRepo).deleteById(1L);

        venderService.deleteById(1L);

        verify(venderRepo, times(1)).deleteById(1L);
    }

    @Test
    void deleteVender_nonExistentId_propagatesException() {
        doThrow(new org.springframework.dao.EmptyResultDataAccessException(1))
                .when(venderRepo).deleteById(999L);

        assertThrows(org.springframework.dao.EmptyResultDataAccessException.class,
                () -> venderService.deleteById(999L));
    }

    @Test
    void deleteVender_multipleIds_deletesEach() {
        doNothing().when(venderRepo).deleteById(anyLong());

        venderService.deleteById(1L);
        venderService.deleteById(2L);

        verify(venderRepo, times(2)).deleteById(anyLong());
    }

    // ─── GET ──────────────────────────────────────────────────────────────────

    @Test
    void getVender_findById_returnsVender() {
        when(venderRepo.findById(1L)).thenReturn(Optional.of(vender));

        Optional<Vender> result = venderService.findById(1L);

        assertTrue(result.isPresent());
        assertEquals("Ali Traders", result.get().getName());
    }

    @Test
    void getVender_findById_returnsEmptyForUnknownId() {
        when(venderRepo.findById(99L)).thenReturn(Optional.empty());

        Optional<Vender> result = venderService.findById(99L);

        assertFalse(result.isPresent());
    }

    @Test
    void getUserVenders_filteredByUserId_returnsOnlyUserVenders() {
        Vender filter = new Vender();
        filter.setUserId(10L);
        Example<Vender> example = Example.of(filter);

        when(venderRepo.findAll(example)).thenReturn(List.of(vender));

        List<Vender> results = venderService.findAll(example);

        assertEquals(1, results.size());
        assertEquals(10L, results.get(0).getUserId());
    }

    @Test
    void getAllVenders_returnsAllVenders() {
        Vender v2 = new Vender();
        v2.setId(2L);
        v2.setName("Raza Traders");
        v2.setCompany(company);
        when(venderRepo.findAll()).thenReturn(Arrays.asList(vender, v2));

        List<Vender> results = venderService.findAll();

        assertEquals(2, results.size());
    }

    // ─── BUG DOCUMENTATION ────────────────────────────────────────────────────

    /**
     * BUG in VenderController.getAllVender() (line 120):
     * obj.getCompany().getId() is called without a null check on getCompany().
     * If a vender has no associated company (e.g., due to IGNORE on NotFoundAction),
     * this throws a NullPointerException.
     *
     * Fix: add a null check before accessing company fields, same as getUserVender().
     */
    @Test
    void bugDocumentation_venderWithNullCompany_causesNullPointerInGetAllVender() {
        Vender venderWithNullCompany = new Vender();
        venderWithNullCompany.setId(3L);
        venderWithNullCompany.setName("Orphan Trader");
        venderWithNullCompany.setCompany(null); // @NotFound(IGNORE) allows this

        when(venderRepo.findAll()).thenReturn(List.of(venderWithNullCompany));

        List<Vender> results = venderService.findAll();

        assertNotNull(results.get(0));
        // BUG: calling results.get(0).getCompany().getId() here would throw NPE
        // VenderController.getAllVender() does exactly this without a null check
        assertNull(results.get(0).getCompany(),
                "BUG: getAllVender() calls obj.getCompany().getId() without null check — NPE when company is null");
    }
}
