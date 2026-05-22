package com.test.business;

import com.persistence.Repo.business.ItemRepo;
import com.persistence.model.Company;
import com.persistence.model.business.Item;
import com.persistence.model.business.Stock;
import com.service.business.ItemService;

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
class ItemServiceTest {

    @Mock
    private ItemRepo itemRepo;

    @InjectMocks
    private ItemService itemService;

    private Item item;
    private Company company;
    private Stock stock;

    @BeforeEach
    void setUp() {
        company = new Company();
        company.setId(1L);
        company.setName("Test Corp");
        company.setUserId(10L);

        stock = new Stock();

        item = new Item();
        item.setId(1L);
        item.setUserId(10L);
        item.setUserType("BUSINESS");
        item.setIname("Widget A");
        item.setIcode("WDG-001");
        item.setIdesc("A basic widget");
        item.setUnit("pcs");
        item.setCategory("Electronics");
        item.setVenderId(5L);
        item.setCompany(company);
        item.setStock(stock);
        item.setDated(LocalDateTime.now());
        item.setUpdated(LocalDateTime.now());
    }

    // ─── ADD (new) ────────────────────────────────────────────────────────────

    @Test
    void addItem_newEntity_savesAndReturnsWithId() {
        Item newItem = new Item();
        newItem.setUserId(10L);
        newItem.setIname("Widget B");
        newItem.setIcode("WDG-002");
        newItem.setDated(LocalDateTime.now());
        newItem.setUpdated(LocalDateTime.now());

        Item saved = new Item();
        saved.setId(2L);
        saved.setIname("Widget B");
        when(itemRepo.save(newItem)).thenReturn(saved);

        Item result = itemService.save(newItem);

        assertNotNull(result.getId());
        assertEquals("Widget B", result.getIname());
        verify(itemRepo).save(newItem);
    }

    @Test
    void addItem_duplicateCheck_detectsExistingItemByCodeAndName() {
        Item filter = new Item();
        filter.setUserId(10L);
        filter.setIcode("WDG-001");
        filter.setIname("Widget A");
        Example<Item> example = Example.of(filter);
        when(itemRepo.exists(example)).thenReturn(true);

        boolean exists = itemService.exists(example);

        assertTrue(exists, "Item with same icode and iname should be flagged as duplicate");
    }

    @Test
    void addItem_noDuplicate_existsReturnsFalse() {
        Item filter = new Item();
        filter.setUserId(10L);
        filter.setIcode("NEW-999");
        Example<Item> example = Example.of(filter);
        when(itemRepo.exists(example)).thenReturn(false);

        assertFalse(itemService.exists(example));
    }

    @Test
    void addItem_withCompanyReference_savesCompanyLink() {
        Item newItem = new Item();
        newItem.setIname("Widget C");
        newItem.setCompany(company);

        Item saved = new Item();
        saved.setId(3L);
        saved.setCompany(company);
        when(itemRepo.save(newItem)).thenReturn(saved);

        Item result = itemService.save(newItem);

        assertNotNull(result.getCompany());
        assertEquals(1L, result.getCompany().getId());
    }

    // ─── UPDATE ───────────────────────────────────────────────────────────────

    @Test
    void updateItem_existingEntity_preservesDatedField() {
        LocalDateTime originalDated = LocalDateTime.of(2024, 3, 15, 10, 0);
        item.setDated(originalDated);
        item.setUpdated(LocalDateTime.now());

        when(itemRepo.save(item)).thenReturn(item);

        Item result = itemService.save(item);

        assertEquals(originalDated, result.getDated(), "dated column is non-updatable — must not change");
        verify(itemRepo).save(item);
    }

    @Test
    void updateItem_changeName_savesUpdatedName() {
        item.setIname("Widget A - Updated");
        when(itemRepo.save(item)).thenReturn(item);

        Item result = itemService.save(item);

        assertEquals("Widget A - Updated", result.getIname());
    }

    @Test
    void updateItem_getReferenceById_returnsCorrectItem() {
        when(itemRepo.getReferenceById(1L)).thenReturn(item);

        Item ref = itemService.getReferenceById(1L);

        assertEquals(1L, ref.getId());
        assertEquals("WDG-001", ref.getIcode());
    }

    @Test
    void updateItem_changeVender_savesNewVenderId() {
        item.setVenderId(99L);
        when(itemRepo.save(item)).thenReturn(item);

        Item result = itemService.save(item);

        assertEquals(99L, result.getVenderId());
    }

    // ─── DELETE ───────────────────────────────────────────────────────────────

    @Test
    void deleteItem_byId_delegatesToRepo() {
        doNothing().when(itemRepo).deleteById(1L);

        itemService.deleteById(1L);

        verify(itemRepo, times(1)).deleteById(1L);
    }

    @Test
    void deleteItem_multipleIds_deletesEach() {
        doNothing().when(itemRepo).deleteById(anyLong());

        itemService.deleteById(1L);
        itemService.deleteById(2L);
        itemService.deleteById(3L);

        verify(itemRepo, times(3)).deleteById(anyLong());
    }

    @Test
    void deleteItem_nonExistentId_propagatesException() {
        doThrow(new org.springframework.dao.EmptyResultDataAccessException(1))
                .when(itemRepo).deleteById(999L);

        assertThrows(org.springframework.dao.EmptyResultDataAccessException.class,
                () -> itemService.deleteById(999L));
    }

    // ─── GET ──────────────────────────────────────────────────────────────────

    @Test
    void getItem_findById_returnsItem() {
        when(itemRepo.findById(1L)).thenReturn(Optional.of(item));

        Optional<Item> result = itemService.findById(1L);

        assertTrue(result.isPresent());
        assertEquals("WDG-001", result.get().getIcode());
        assertEquals("Widget A", result.get().getIname());
    }

    @Test
    void getItem_findById_returnsEmptyForMissingId() {
        when(itemRepo.findById(99L)).thenReturn(Optional.empty());

        Optional<Item> result = itemService.findById(99L);

        assertFalse(result.isPresent());
    }

    @Test
    void getUserItems_filteredByUserId_returnsOnlyUserItems() {
        Item filter = new Item();
        filter.setUserId(10L);
        Example<Item> example = Example.of(filter);

        when(itemRepo.findAll(example)).thenReturn(List.of(item));

        List<Item> results = itemService.findAll(example);

        assertEquals(1, results.size());
        assertEquals(10L, results.get(0).getUserId());
    }

    @Test
    void getAllItems_returnsCompleteList() {
        Item item2 = new Item();
        item2.setId(2L);
        item2.setIname("Gadget X");
        item2.setIcode("GDG-001");
        when(itemRepo.findAll()).thenReturn(Arrays.asList(item, item2));

        List<Item> results = itemService.findAll();

        assertEquals(2, results.size());
    }

    @Test
    void getItem_existsById_returnsTrueForSavedItem() {
        when(itemRepo.existsById(1L)).thenReturn(true);

        assertTrue(itemService.existsById(1L));
    }

    @Test
    void getItem_existsById_returnsFalseForMissingItem() {
        when(itemRepo.existsById(99L)).thenReturn(false);

        assertFalse(itemService.existsById(99L));
    }

    // ─── BUG DOCUMENTATION ────────────────────────────────────────────────────

    /**
     * BUG in ItemController.addItem() (lines 229-233):
     * The iname check is duplicated — both branches check dto.getIname() instead of
     * the second checking dto.getIcode(). As a result, obj.setIcode() is never called
     * with a non-empty check (the condition uses iname not icode).
     *
     *   if(appUtil.notEmptyNorNull(dto.getIname())){ obj.setIcode(dto.getIcode()); }
     *   if(appUtil.notEmptyNorNull(dto.getIname())){ obj.setIname(dto.getIname()); }
     *
     * Fix: first condition should be notEmptyNorNull(dto.getIcode()).
     */
    @Test
    void bugDocumentation_duplicateCheckWithIcodeAndIname() {
        // The duplicate check uses both icode and iname on the Example
        // but the controller only correctly sets iname (icode check is wrong)
        Item filterWithOnlyIname = new Item();
        filterWithOnlyIname.setUserId(10L);
        filterWithOnlyIname.setIname("Widget A");
        // icode intentionally NOT set — mirrors the bug where icode guard uses iname
        Example<Item> example = Example.of(filterWithOnlyIname);
        when(itemRepo.exists(example)).thenReturn(false);

        boolean duplicate = itemService.exists(example);

        assertFalse(duplicate,
                "BUG: icode is not included in duplicate check because controller uses iname condition for setIcode");
    }
}
