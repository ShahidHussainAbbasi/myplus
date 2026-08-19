package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.enums.CustomerType;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.common.imports.ColumnSpec;
import com.myplus.common.imports.CsvReader;
import com.myplus.common.imports.ImportSpec;

/**
 * Slice I1 — what a Customer CSV import consists of.
 *
 * <h3>Its own create path, and that is a decision</h3>
 * This does NOT go through {@code CustomerService.saveUpdateCustomer}. That method is the SALE path's
 * resolve-or-create, and it matches an existing customer by Query-By-Example on a probe built <i>after</i>
 * {@code setUserId(actor)} — so the probe includes the acting user. That is the O7 D2c defect: an outlet
 * created by the owner cannot be matched by someone else's save, and a duplicate customer is created with no
 * credit limit. An import running through it would manufacture that defect in bulk.
 *
 * <p>It also does not go through {@code CustomerController.addCustomer}, whose duplicate check loads every
 * customer the caller owns and filters in memory — a full scan per row (§ {@link #existingKeys}).
 *
 * <h3>What an imported customer deliberately does NOT get</h3>
 * <ul>
 *   <li><b>A balance.</b> {@code dueAmount} and {@code creditBalance} are cached figures owned by
 *       {@code recomputeDue} and the store-credit ledger. A number typed into a spreadsheet with no invoices
 *       behind it would put the master and the ledger permanently out of agreement. Both start at zero, and a
 *       file that carries either COLUMN is refused whole — silently dropping it would let an operator believe
 *       the balances went in.</li>
 *   <li><b>An account group.</b> Like any newly registered customer it is its own credit account (P4a) and
 *       falls back to its own limit, never to "no limit". A chain's hierarchy is set after the import.</li>
 *   <li><b>A territory.</b> {@code assignedRepUserId} stays null, which under D2d reads as "visible to every
 *       rep" — the same as any manually created outlet, not a special case.</li>
 * </ul>
 */
@Component
public class CustomerImportSpec implements ImportSpec<Customer> {

    private static final Logger LOG = LoggerFactory.getLogger(CustomerImportSpec.class);

    static final String NAME = "name";
    static final String CONTACT = "contact";
    static final String EMAIL = "email";
    static final String ADDRESS = "address";
    static final String CITY = "city";
    static final String CNIC = "cnic";
    static final String LICENSE_NO = "licenseNo";
    static final String LICENSE_EXPIRY = "licenseExpiry";
    static final String CUSTOMER_TYPE = "customerType";
    static final String CREDIT_LIMIT = "creditLimit";
    static final String PAYMENT_TERMS = "paymentTermsDays";

    @Autowired private CustomerRepo customerRepo;

    @Autowired(required = false)
    private PartyBridgeService partyBridgeService;

    @Override public String entity() { return "customer"; }

    @Override public String label() { return "Customers"; }

    /**
     * The template's columns — and the same list validates the upload, so the header cannot drift from the
     * parser.
     *
     * <p>Every optional column may be left blank. {@code customerType} blank means {@code WALK_IN}, matching
     * {@code addCustomer}'s {@code CustomerType.orDefault} exactly: a customer whose channel is unset must
     * mean the same thing however the row was created, or every downstream consumer needs its own null rule.
     */
    @Override
    public List<ColumnSpec> columns() {
        return Arrays.asList(
                ColumnSpec.text(NAME, true, 255, "Irfan Medical Store"),
                ColumnSpec.text(CONTACT, true, 255, "03001234567"),
                ColumnSpec.text(EMAIL, false, 255, "shop@example.com"),
                ColumnSpec.text(ADDRESS, false, 255, "Main Bazaar"),
                ColumnSpec.text(CITY, false, 80, "Lahore"),
                ColumnSpec.text(CNIC, false, 20, "35202-1234567-1"),
                ColumnSpec.text(LICENSE_NO, false, 60, "DL-12345"),
                ColumnSpec.date(LICENSE_EXPIRY, false),
                ColumnSpec.oneOf(CUSTOMER_TYPE, false, "WALK_IN", "RETAILER", "WHOLESALE", "VIP"),
                ColumnSpec.number(CREDIT_LIMIT, false, "50000"),
                ColumnSpec.integer(PAYMENT_TERMS, false, "30"));
    }

    /**
     * The contact as written (trimmed) — not the name.
     *
     * <p><b>Why contact:</b> two branches of one chain legitimately share a name, and the registration
     * screen's name-only check already lets duplicates through between two users in one org. Contact is the
     * field the sale path's own matching leans on, so using it here keeps the import agreeing with the till.
     *
     * <p><b>Why trimmed and not whitespace-stripped:</b> the key has to be comparable to the stored column by
     * a plain {@code IN}, or {@code idx_customer_org_contact} cannot serve the query and the batched check
     * degrades into the full scan it exists to replace. Stripping internal spaces would need
     * {@code REPLACE(contact,' ','')} in SQL, which no index can answer.
     *
     * <p>The cost, stated plainly: a customer already stored as {@code "0300 1234567"} is NOT recognised as
     * the file's {@code "03001234567"} and would be created a second time. That is the same comparison the
     * rest of the application makes, so the import is no worse than the counter — but it is a real limitation,
     * and it is why the preview lists what it will create before anything is written.
     */
    @Override
    public String duplicateKey(CsvReader.Row row) {
        return row.get(CONTACT);   // Row.get already trims, and maps blank to null
    }

    /**
     * Which contacts this tenant already has — ONE query for the whole file, never one per row.
     *
     * <p>A plain {@code IN} over a projection, so {@code idx_customer_org_contact} (V41) serves it. Contrast
     * {@code CustomerController.addCustomer}, which loads every customer the caller owns and filters in
     * memory: invisible for a single save, O(n²) for a 2 000-row import.
     */
    @Override
    public Set<String> existingKeys(Long orgId, Long userId, Set<String> keys) {
        List<String> stored = customerRepo.existingContactsScoped(orgId, userId, new ArrayList<>(keys));
        Set<String> hit = new HashSet<>();
        for (String s : stored) {
            if (s == null) continue;
            String trimmed = s.trim();
            if (keys.contains(trimmed)) hit.add(trimmed);
        }
        return hit;
    }

    @Override
    public Customer build(CsvReader.Row row, Long orgId, Long userId) {
        Customer c = new Customer();
        c.setName(row.get(NAME));
        c.setContact(row.get(CONTACT));
        c.setEmail(row.get(EMAIL));
        c.setAddress(row.get(ADDRESS));
        c.setCity(row.get(CITY));
        c.setCnic(row.get(CNIC));
        c.setLicenseNo(row.get(LICENSE_NO));

        String expiry = row.get(LICENSE_EXPIRY);
        if (expiry != null) c.setLicenseExpiry(LocalDate.parse(expiry));

        String type = row.get(CUSTOMER_TYPE);
        // orDefault, exactly as addCustomer does — never leave the channel unknown.
        c.setCustomerType(CustomerType.orDefault(
                type == null ? null : CustomerType.valueOf(type.trim().toUpperCase())));

        String limit = row.get(CREDIT_LIMIT);
        if (limit != null) c.setCreditLimit(new BigDecimal(limit.replace(",", "")));

        String terms = row.get(PAYMENT_TERMS);
        if (terms != null) c.setPaymentTermsDays(Integer.valueOf(terms.trim()));

        // Balances are NOT importable. Zero, not null, so the non-null-ready column has a value — the same
        // seed saveUpdateCustomer gives a brand-new customer. recomputeDue owns it from the first invoice on.
        c.setDueAmount(BigDecimal.ZERO);

        LocalDateTime now = LocalDateTime.now();
        c.setDated(now);
        c.setUpdated(now);
        c.setUserId(userId);              // audit: who created the row
        c.setOrganizationId(orgId);       // tenant scope
        return c;
    }

    /**
     * One {@code saveAll} for the whole batch, then a best-effort party bridge per row.
     *
     * <p>The bridge is deliberately outside the save and deliberately swallowed: registering customers is core
     * POS work that predates the party master, and an unwired or unavailable party-service must not turn a
     * good import into a failure. The consequence is recorded in the slice doc — imported rows may need
     * re-bridging, and there is no sweeper for that yet.
     */
    @Override
    public int persist(List<Customer> batch) {
        List<Customer> saved = customerRepo.saveAll(batch);
        if (partyBridgeService != null) {
            for (Customer c : saved) {
                try {
                    partyBridgeService.bridgeCustomer(c);
                } catch (Exception e) {
                    LOG.warn("I1: party bridge failed for imported customer {} — import stands, "
                            + "the row is unbridged until re-saved", c.getCustomerId(), e);
                }
            }
        }
        return saved.size();
    }
}
