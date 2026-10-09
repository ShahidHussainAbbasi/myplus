package com.myplus.expense.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.GlAccountView;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.dto.ExpenseDtos.CategoryRequest;
import com.myplus.expense.dto.ExpenseDtos.CategoryView;
import com.myplus.expense.entity.ExpenseCategory;
import com.myplus.expense.repository.ExpenseCategoryRepo;

import lombok.RequiredArgsConstructor;

/**
 * Expense categories: the owner's words, each mapped to a ledger account.
 *
 * <h3>Defaults, seeded lazily per tenant</h3>
 * A business that switches the module on gets the everyday categories at once, mapped to the 6000-series accounts
 * finance seeds (EX-0b). Seeded on the first read, idempotently: the UNIQUE (organization_id, code) index settles
 * a race, exactly as finance's ensureDefaults does.
 *
 * <h3>The purchase/expense boundary is checked when a category is SAVED</h3>
 * Finance refuses a non-EXPENSE debit on every posting. Checking at save as well means an owner who maps
 * "Stock for resale" to 1200 Inventory is told once, on the screen where they did it, rather than by every
 * expense that would later fail to post.
 */
@Service
@RequiredArgsConstructor
public class ExpenseCategoryService {

    /** {code, name, account}. Names a shopkeeper would use; accounts from finance's EX-0b defaults. */
    static final String[][] DEFAULTS = {
            {"RENT", "Rent", "6000"},
            {"UTILITIES", "Electricity, gas and water", "6100"},
            {"FUEL", "Fuel and transport", "6200"},
            {"REPAIRS", "Repairs and maintenance", "6300"},
            {"MARKETING", "Marketing and advertising", "6400"},
            {"BANK_CHARGES", "Bank charges", "6500"},
            {"OFFICE", "Office supplies and stationery", "6600"},
            {"OTHER", "Other expenses", "6900"},
    };

    private final ExpenseCategoryRepo repo;
    private final ExpenseAccess access;
    private final FinanceClient finance;
    private final ExpenseAuditService audit;
    private final org.springframework.beans.factory.ObjectProvider<ExpenseCategoryService> self;

    /**
     * NOT wrapped in a transaction, deliberately. The seed commits in its own; a surrounding read-only
     * transaction would have taken its REPEATABLE READ snapshot at the count, and the read that follows would
     * not see the rows just seeded — a tenant's first list would come back empty.
     */
    public List<CategoryView> list() {
        Long org = access.org();
        if (repo.countByOrganizationId(org) == 0) self.getObject().seedDefaults(org);
        return repo.findByOrganizationIdOrderBySortOrderAscNameAsc(org).stream().map(CategoryView::of).toList();
    }

    /** Its own transaction, so a read can seed and a concurrent seeder's duplicate costs one row, not the list. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void seedDefaults(Long org) {
        int order = 10;
        for (String[] d : DEFAULTS) {
            if (repo.existsByOrganizationIdAndCode(org, d[0])) { order += 10; continue; }
            try {
                repo.saveAndFlush(newCategory(org, d[0], d[1], d[2], order));
            } catch (DataIntegrityViolationException lostRace) {
                // another request seeded this code first — the row we wanted exists
            }
            order += 10;
        }
    }

    @Transactional
    public CategoryView create(CategoryRequest r) {
        access.assertModuleOn();
        Long org = access.org();
        String name = requireName(r.name());
        if (repo.existsByOrganizationIdAndNameIgnoreCase(org, name))
            throw new ValidationException("There is already a category called " + name + ".");
        String account = requireExpenseAccount(r.accountCode());
        String code;
        if (r.code() == null || r.code().isBlank()) {
            // EX-2e — the screen asks only for a name; the code is derived from it, numbered if already taken
            String base = normaliseCode(name.length() > 28 ? name.substring(0, 28) : name);
            code = base;
            for (int n = 2; repo.existsByOrganizationIdAndCode(org, code); n++) code = base + "_" + n;
        } else {
            code = normaliseCode(r.code());
            if (repo.existsByOrganizationIdAndCode(org, code))
                throw new ValidationException("A category with the code " + code + " already exists.");
        }
        ExpenseCategory c = newCategory(org, code, name, account, r.sortOrder() == null ? 500 : r.sortOrder());
        if (r.active() != null) c.setActive(r.active());
        try {
            c = repo.saveAndFlush(c);
        } catch (DataIntegrityViolationException dup) {
            throw new ValidationException("A category with the code " + code + " already exists.");
        }
        audit.record("CATEGORY_CREATED", "EXPENSE_CATEGORY", code, null, name + " -> " + account, null);
        return CategoryView.of(c);
    }

    @Transactional
    public CategoryView update(Long id, CategoryRequest r) {
        access.assertModuleOn();
        ExpenseCategory c = repo.findByIdAndOrganizationId(id, access.org())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        String before = c.getAccountCode();
        if (r.name() != null) {
            String name = requireName(r.name());
            if (repo.existsByOrganizationIdAndNameIgnoreCaseAndIdNot(c.getOrganizationId(), name, c.getId()))
                throw new ValidationException("There is already a category called " + name + ".");
            c.setName(name);
        }
        if (r.accountCode() != null) c.setAccountCode(requireExpenseAccount(r.accountCode()));
        if (Boolean.FALSE.equals(r.active()) && Boolean.TRUE.equals(c.getActive())
                && repo.countByOrganizationIdAndActiveTrue(c.getOrganizationId()) <= 1)
            throw new ValidationException("Keep at least one category switched on, or nobody can record an expense.");
        if (r.active() != null) c.setActive(r.active());
        if (r.sortOrder() != null) c.setSortOrder(r.sortOrder());
        c.setUpdatedAt(LocalDateTime.now());
        // EX-2e — the trail says what changed, not only the account: a rename or a switch-off is a change too
        audit.record("CATEGORY_CHANGED", "EXPENSE_CATEGORY", c.getCode(), null,
                c.getName() + ": " + before + " -> " + c.getAccountCode() + (Boolean.TRUE.equals(c.getActive()) ? "" : " (off)"), null);
        return CategoryView.of(c);
    }

    /**
     * EX-2e — the accounts a category may point at, for the screen's list: this business's EXPENSE accounts, never
     * 5000 (goods bought for stock are a purchase). The same rule {@link #requireExpenseAccount} enforces on save.
     */
    public List<com.myplus.expense.dto.ExpenseDtos.ExpenseAccountView> expenseAccounts() {
        List<GlAccountView> chart = finance.ensureDefaultAccounts();
        return (chart == null ? List.<GlAccountView>of() : chart).stream()
                .filter(a -> "EXPENSE".equals(a.getType()) && !"5000".equals(a.getCode()))
                .sorted(java.util.Comparator.comparing(GlAccountView::getCode))
                .map(a -> new com.myplus.expense.dto.ExpenseDtos.ExpenseAccountView(a.getCode(), a.getName()))
                .toList();
    }

    /**
     * EX-2e — for a TILL PAY-OUT: the category the cashier chose, even if the owner has switched it off since. The
     * cash left the drawer when it was chosen; refusing it here would leave money out of the books for good (the
     * relay dead-letters a refusal), now that switching a category off is one click on the Categories screen.
     */
    ExpenseCategory categoryForDrawer(Long org, Long id) {
        if (id == null) throw new ValidationException("Choose a category for each line.");
        return repo.findByIdAndOrganizationId(id, org)
                .orElseThrow(() -> new ValidationException("That category does not exist in this business."));
    }

    /** For a voucher line: the tenant's ACTIVE category, or a refusal naming the problem. */
    ExpenseCategory activeCategory(Long org, Long id) {
        if (id == null) throw new ValidationException("Choose a category for each line.");
        ExpenseCategory c = repo.findByIdAndOrganizationId(id, org)
                .orElseThrow(() -> new ValidationException("That category does not exist in this business."));
        if (!Boolean.TRUE.equals(c.getActive())) throw new ValidationException("The category " + c.getName() + " is switched off.");
        return c;
    }

    private String requireExpenseAccount(String code) {
        if (code == null || code.isBlank()) throw new ValidationException("Choose the ledger account for this category.");
        String want = code.trim();
        List<GlAccountView> chart = finance.ensureDefaultAccounts();
        GlAccountView acct = chart == null ? null
                : chart.stream().filter(a -> want.equals(a.getCode())).findFirst().orElse(null);
        if (acct == null) throw new ValidationException("Account " + want + " is not in this business's chart of accounts.");
        if (!"EXPENSE".equals(acct.getType()) || "5000".equals(want))
            throw new ValidationException("Account " + want + " (" + acct.getName() + ") is not an expense account. "
                    + "Goods bought for stock are a purchase, not an expense.");
        return want;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new ValidationException("Give the category a name.");
        String n = name.trim();
        if (n.length() > 120) throw new ValidationException("A category name can be at most 120 characters.");
        return n;
    }

    private static String normaliseCode(String code) {
        if (code == null || code.isBlank()) throw new ValidationException("Give the category a short code.");
        String c = code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        if (c.length() > 32) throw new ValidationException("A category code can be at most 32 characters.");
        return c;
    }

    private static ExpenseCategory newCategory(Long org, String code, String name, String account, int order) {
        ExpenseCategory c = new ExpenseCategory();
        c.setOrganizationId(org);
        c.setCode(code);
        c.setName(name);
        c.setAccountCode(account);
        c.setActive(Boolean.TRUE);
        c.setSortOrder(order);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }
}
