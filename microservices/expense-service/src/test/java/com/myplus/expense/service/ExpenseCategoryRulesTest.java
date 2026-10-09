package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.GlAccountView;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.dto.ExpenseDtos.CategoryRequest;
import com.myplus.expense.entity.ExpenseCategory;
import com.myplus.expense.repository.ExpenseCategoryRepo;

/** EX-2e — the rules behind the Categories screen. */
class ExpenseCategoryRulesTest {

    private final ExpenseCategoryRepo repo = mock(ExpenseCategoryRepo.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);
    private final FinanceClient finance = mock(FinanceClient.class);
    private ExpenseCategoryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(access.org()).thenReturn(7L);
        when(finance.ensureDefaultAccounts()).thenReturn(List.of(
                new GlAccountView(1L, "1000", "Cash", "ASSET"),
                new GlAccountView(2L, "5000", "Cost of Goods Sold", "EXPENSE"),
                new GlAccountView(3L, "6100", "Utilities", "EXPENSE"),
                new GlAccountView(4L, "6000", "Rent", "EXPENSE")));
        when(repo.saveAndFlush(any(ExpenseCategory.class))).thenAnswer(i -> i.getArgument(0));
        service = new ExpenseCategoryService(repo, access, finance, mock(ExpenseAuditService.class), mock(ObjectProvider.class));
    }

    private static ExpenseCategory cat(long id, String name, boolean active) {
        ExpenseCategory c = new ExpenseCategory();
        c.setId(id);
        c.setOrganizationId(7L);
        c.setCode(name.toUpperCase());
        c.setName(name);
        c.setAccountCode("6000");
        c.setActive(active);
        return c;
    }

    @Test
    @DisplayName("the screen offers expense accounts only, in code order — never 5000 or a non-expense account")
    void accountsAreExpenseOnly() {
        assertThat(service.expenseAccounts()).extracting(a -> a.code()).containsExactly("6000", "6100");
    }

    @Test
    @DisplayName("⭐ a new category needs only a name: its code is derived, and numbered when already taken")
    void codeIsDerivedFromTheName() {
        when(repo.existsByOrganizationIdAndCode(7L, "STAFF_TEA")).thenReturn(true);
        ArgumentCaptor<ExpenseCategory> saved = ArgumentCaptor.forClass(ExpenseCategory.class);
        when(repo.saveAndFlush(saved.capture())).thenAnswer(i -> i.getArgument(0));

        service.create(new CategoryRequest(null, "Staff tea", "6100", null, null));

        assertThat(saved.getValue().getCode()).isEqualTo("STAFF_TEA_2");
        assertThat(saved.getValue().getAccountCode()).isEqualTo("6100");
    }

    @Test
    @DisplayName("two categories may not share a name (on create or on rename) — the owner could not tell them apart")
    void namesAreUnique() {
        when(repo.existsByOrganizationIdAndNameIgnoreCase(7L, "Rent")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CategoryRequest(null, "Rent", "6000", null, null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("already a category called Rent");

        when(repo.findByIdAndOrganizationId(9L, 7L)).thenReturn(Optional.of(cat(9L, "Fuel", true)));
        when(repo.existsByOrganizationIdAndNameIgnoreCaseAndIdNot(7L, "rent", 9L)).thenReturn(true);
        assertThatThrownBy(() -> service.update(9L, new CategoryRequest(null, "rent", null, null, null)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("⭐ the last category switched on cannot be switched off — or nobody could record an expense")
    void lastActiveStaysOn() {
        when(repo.findByIdAndOrganizationId(9L, 7L)).thenReturn(Optional.of(cat(9L, "Rent", true)));
        when(repo.countByOrganizationIdAndActiveTrue(7L)).thenReturn(1L);
        assertThatThrownBy(() -> service.update(9L, new CategoryRequest(null, null, null, false, null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("at least one category");

        when(repo.countByOrganizationIdAndActiveTrue(7L)).thenReturn(2L);
        assertThat(service.update(9L, new CategoryRequest(null, null, null, false, null)).active()).isFalse();
    }

    @Test
    @DisplayName("a category cannot be pointed at Cost of Goods Sold — goods for stock are a purchase")
    void stockAccountRefused() {
        when(repo.findByIdAndOrganizationId(9L, 7L)).thenReturn(Optional.of(cat(9L, "Rent", true)));
        assertThatThrownBy(() -> service.update(9L, new CategoryRequest(null, null, "5000", null, null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("not an expense account");
    }

    @Test
    @DisplayName("⭐ a till pay-out keeps the category the cashier chose even if it was switched off since; a typed form does not")
    void drawerAcceptsSwitchedOff() {
        when(repo.findByIdAndOrganizationId(9L, 7L)).thenReturn(Optional.of(cat(9L, "Rent", false)));
        assertThat(service.categoryForDrawer(7L, 9L).getName()).isEqualTo("Rent");
        assertThatThrownBy(() -> service.activeCategory(7L, 9L))
                .isInstanceOf(ValidationException.class).hasMessageContaining("switched off");
        when(repo.findByIdAndOrganizationId(10L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.categoryForDrawer(7L, 10L)).isInstanceOf(ValidationException.class);
    }
}
