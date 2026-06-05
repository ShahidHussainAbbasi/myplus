// package com.test.business;

// import com.persistence.Repo.business.CompanyRepo;
// import com.persistence.model.Company;
// import com.service.business.CompanyService;

// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.extension.ExtendWith;
// import org.mockito.InjectMocks;
// import org.mockito.Mock;
// import org.mockito.junit.jupiter.MockitoExtension;
// import org.springframework.data.domain.Example;

// import java.time.LocalDateTime;
// import java.util.Arrays;
// import java.util.List;
// import java.util.Optional;

// import static org.junit.jupiter.api.Assertions.*;
// import static org.mockito.ArgumentMatchers.*;
// import static org.mockito.Mockito.*;

// @ExtendWith(MockitoExtension.class)
// class CompanyServiceTest {

//     @Mock
//     private CompanyRepo companyRepo;

//     @InjectMocks
//     private CompanyService companyService;

//     private Company company;

//     @BeforeEach
//     void setUp() {
//         company = new Company();
//         company.setId(1L);
//         company.setUserId(10L);
//         company.setUserType("BUSINESS");
//         company.setName("Test Corp");
//         company.setEmail("test@corp.com");
//         company.setMobile("03001234567");
//         company.setDated(LocalDateTime.now());
//         company.setUpdated(LocalDateTime.now());
//     }

//     // ─── ADD (new) ────────────────────────────────────────────────────────────

//     @Test
//     void addCompany_newEntity_savesAndReturnsWithId() {
//         Company newCompany = new Company();
//         newCompany.setUserId(10L);
//         newCompany.setName("New Corp");
//         newCompany.setDated(LocalDateTime.now());
//         newCompany.setUpdated(LocalDateTime.now());

//         Company saved = new Company();
//         saved.setId(2L);
//         saved.setName("New Corp");
//         when(companyRepo.save(newCompany)).thenReturn(saved);

//         Company result = companyService.save(newCompany);

//         assertNotNull(result.getId());
//         assertEquals("New Corp", result.getName());
//         verify(companyRepo, times(1)).save(newCompany);
//     }

//     @Test
//     void addCompany_duplicateCheck_existsReturnsTrueForSameUserAndName() {
//         company.setId(null);
//         Example<Company> example = Example.of(company);
//         when(companyRepo.exists(example)).thenReturn(true);

//         boolean exists = companyService.exists(example);

//         assertTrue(exists, "Duplicate company should be detected");
//         verify(companyRepo).exists(example);
//     }

//     @Test
//     void addCompany_noDuplicate_existsReturnsFalse() {
//         company.setId(null);
//         Example<Company> example = Example.of(company);
//         when(companyRepo.exists(example)).thenReturn(false);

//         boolean exists = companyService.exists(example);

//         assertFalse(exists);
//     }

//     // ─── UPDATE ───────────────────────────────────────────────────────────────

//     @Test
//     void updateCompany_existingEntity_preservesDatedAndSetsUpdated() {
//         LocalDateTime originalDated = LocalDateTime.of(2024, 1, 1, 0, 0);
//         company.setDated(originalDated);
//         company.setUpdated(LocalDateTime.now());

//         when(companyRepo.save(company)).thenReturn(company);

//         Company result = companyService.save(company);

//         assertEquals(originalDated, result.getDated(), "Original dated must be preserved on update");
//         assertNotNull(result.getUpdated());
//         verify(companyRepo).save(company);
//     }

//     @Test
//     void updateCompany_getReferenceById_returnsExistingEntity() {
//         when(companyRepo.getReferenceById(1L)).thenReturn(company);

//         Company ref = companyService.getReferenceById(1L);

//         assertEquals(company.getId(), ref.getId());
//         assertEquals(company.getName(), ref.getName());
//     }

//     // ─── DELETE ───────────────────────────────────────────────────────────────

//     @Test
//     void deleteCompany_byId_delegatesToRepo() {
//         doNothing().when(companyRepo).deleteById(1L);

//         companyService.deleteById(1L);

//         verify(companyRepo, times(1)).deleteById(1L);
//     }

//     @Test
//     void deleteCompany_multipleIds_deletesEach() {
//         List<Long> ids = Arrays.asList(1L, 2L, 3L);
//         doNothing().when(companyRepo).deleteById(anyLong());

//         ids.forEach(companyService::deleteById);

//         verify(companyRepo, times(3)).deleteById(anyLong());
//     }

//     // ─── GET ──────────────────────────────────────────────────────────────────

//     @Test
//     void getCompany_findById_returnsPresent() {
//         when(companyRepo.findById(1L)).thenReturn(Optional.of(company));

//         Optional<Company> result = companyService.findById(1L);

//         assertTrue(result.isPresent());
//         assertEquals(1L, result.get().getId());
//     }

//     @Test
//     void getCompany_findById_returnsEmpty_whenNotFound() {
//         when(companyRepo.findById(99L)).thenReturn(Optional.empty());

//         Optional<Company> result = companyService.findById(99L);

//         assertFalse(result.isPresent());
//     }

//     @Test
//     void getAllCompanies_returnsAll() {
//         Company c2 = new Company();
//         c2.setId(2L);
//         c2.setName("Second Corp");
//         when(companyRepo.findAll()).thenReturn(Arrays.asList(company, c2));

//         List<Company> results = companyService.findAll();

//         assertEquals(2, results.size());
//     }

//     @Test
//     void getUserCompanies_filteredByUserId_returnsOnlyUserCompanies() {
//         Company filter = new Company();
//         filter.setUserId(10L);
//         Example<Company> example = Example.of(filter);

//         when(companyRepo.findAll(example)).thenReturn(List.of(company));

//         List<Company> results = companyService.findAll(example);

//         assertEquals(1, results.size());
//         assertEquals(10L, results.get(0).getUserId());
//     }

//     @Test
//     void getCompany_count_returnsCorrectCount() {
//         when(companyRepo.count()).thenReturn(5L);

//         long count = companyService.count();

//         assertEquals(5L, count);
//     }

//     // ─── BUG DOCUMENTATION ────────────────────────────────────────────────────

//     /**
//      * BUG in CompanyController.addCompany() (line 126):
//      * Example.of(obj) is created BEFORE obj.setUserId() and obj.setName() are called.
//      * This means the duplicate check uses an empty Example that matches ALL companies,
//      * causing any add attempt to be rejected as a duplicate when ANY company exists.
//      *
//      * Fix: move Example.of(obj) creation to AFTER setting userId and name.
//      */
//     @Test
//     void bugDocumentation_emptyExampleMatchesAllCompanies() {
//         Company emptyFilter = new Company(); // no userId, no name set
//         Example<Company> emptyExample = Example.of(emptyFilter);

//         when(companyRepo.exists(emptyExample)).thenReturn(true);

//         // An empty example will match any existing record
//         boolean existsWithEmptyFilter = companyService.exists(emptyExample);
//         assertTrue(existsWithEmptyFilter,
//                 "BUG: empty Example matches all records — controller creates Example before setting filter fields");
//     }
// }
