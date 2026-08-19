package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.enums.CustomerType;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.common.imports.ColumnSpec;
import com.myplus.common.imports.ImportEngine;
import com.myplus.common.imports.ImportReport;

/**
 * Slice I1 — the Customer spec: which columns exist, what a duplicate is, and what an imported row is built
 * with. Pure Mockito, no container, runs on every {@code mvn test}.
 *
 * <p>Driven through the real {@link ImportEngine} rather than by calling the spec's methods directly, because
 * the behaviour worth pinning down is what an operator's FILE does — the engine is the thing that decides
 * which spec method runs for which row, and stubbing it out would test the parts while leaving the wiring
 * unexercised.
 *
 * <p>⚠ The complete field set is injected, including {@code partyBridgeService}, which no case below reaches.
 * A hand-built service with a field left null is this codebase's recurring trap (three times on
 * {@code SagaSellService} alone) — nothing warns until some later slice's path dereferences it.
 */
class CustomerImportSpecTest {

    private static final Long ORG = 1L, USER = 7L;

    private static final String HEADERS =
            "name,contact,email,address,city,cnic,licenseNo,licenseExpiry,customerType,creditLimit,paymentTermsDays\n";

    private CustomerRepo customerRepo;
    private PartyBridgeService partyBridgeService;
    private CustomerImportSpec spec;
    private ImportEngine engine;

    @BeforeEach
    void setUp() {
        customerRepo = mock(CustomerRepo.class);
        partyBridgeService = mock(PartyBridgeService.class);
        spec = new CustomerImportSpec();
        ReflectionTestUtils.setField(spec, "customerRepo", customerRepo);
        ReflectionTestUtils.setField(spec, "partyBridgeService", partyBridgeService);
        engine = new ImportEngine();

        // Nothing exists unless a case says so.
        when(customerRepo.existingContactsScoped(anyLong(), anyLong(), any()))
                .thenReturn(new ArrayList<>());
        when(customerRepo.saveAll(any())).thenAnswer(inv -> new ArrayList<>((Collection<Customer>) inv.getArgument(0)));
    }

    private String row(String name, String contact, String rest) {
        return name + "," + contact + "," + rest + "\n";
    }

    /** A full valid row with everything after `contact` left blank. */
    private String minimal(String name, String contact) {
        return row(name, contact, ",,,,,,,,");
    }

    // ── the template ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void balances_are_not_offered_as_columns() {
        List<String> headers = engine.templateHeaders(spec);

        // dueAmount is owned by recomputeDue and creditBalance by the store-credit ledger. Offering either as
        // a column is how a master and its ledger start disagreeing.
        assertThat(headers).doesNotContain("dueAmount", "creditBalance", "paidAmount");
        assertThat(headers).doesNotContain("customerId", "organizationId", "userId", "partyId");
        assertThat(headers).startsWith("name", "contact");
    }

    @Test
    void a_file_carrying_dueAmount_is_refused_whole() {
        ImportReport r = engine.commit(spec, "name,contact,dueAmount\nAli,0300,5000\n", ORG, USER);

        // Refused, not ignored: an operator told "imported successfully" would believe the balances went in.
        assertThat(r.isCommitted()).isFalse();
        assertThat(r.getFileError()).contains("dueAmount");
        verify(customerRepo, never()).saveAll(any());
    }

    // ── required columns ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void name_and_contact_are_required() {
        // Blank in the two required columns but NOT a blank LINE. A wholly empty line is a spreadsheet
        // artefact the reader skips, so it would never reach per-column validation at all.
        ImportReport r = engine.dryRun(spec, HEADERS + row("", "", "shop@example.com,,,,,,,,"), ORG, USER);

        assertThat(r.getRefused()).isEqualTo(1);
        assertThat(r.getRows().get(0).getMessage()).contains("'name'").contains("'contact'");
    }

    @Test
    void a_wholly_blank_line_is_skipped_not_refused() {
        // Excel appends these constantly and the operator cannot see one. Refusing the file over a trailing
        // empty line would redden a perfectly good import for a reason nobody can act on.
        ImportReport r = engine.dryRun(spec, HEADERS + minimal("Ali", "0300") + minimal("", ""), ORG, USER);

        assertThat(r.getFileError()).isNull();
        assertThat(r.getTotal()).isEqualTo(1);      // the blank line is invisible, not a row
        assertThat(r.getToCreate()).isEqualTo(1);
        assertThat(r.getRefused()).isZero();
    }

    @Test
    void one_bad_row_stops_the_good_ones_being_written() {
        String csv = HEADERS + minimal("Irfan Medical", "0300") + minimal("", "0301") + minimal("Sara", "0302");

        ImportReport r = engine.commit(spec, csv, ORG, USER);

        assertThat(r.isCommitted()).isFalse();
        verify(customerRepo, never()).saveAll(any());
    }

    // ── per-column validation ───────────────────────────────────────────────────────────────────────────────

    @Test
    void licenseExpiry_must_be_an_iso_date() {
        ImportReport r = engine.dryRun(spec, HEADERS + row("Ali", "0300", ",,,,,31-12-2027,,,"), ORG, USER);

        assertThat(r.getRefused()).isEqualTo(1);
        assertThat(r.getRows().get(0).getMessage()).contains(ColumnSpec.DATE_FORMAT);
    }

    @Test
    void customerType_must_be_one_the_enum_knows() {
        ImportReport r = engine.dryRun(spec, HEADERS + row("Ali", "0300", ",,,,,,DISTRIBUTOR,,"), ORG, USER);

        assertThat(r.getRefused()).isEqualTo(1);
        assertThat(r.getRows().get(0).getMessage()).contains("customerType");
    }

    @Test
    void creditLimit_must_be_a_number() {
        ImportReport r = engine.dryRun(spec, HEADERS + row("Ali", "0300", ",,,,,,,lots,"), ORG, USER);

        assertThat(r.getRefused()).isEqualTo(1);
        assertThat(r.getRows().get(0).getMessage()).contains("creditLimit");
    }

    // ── duplicates ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void an_existing_contact_is_skipped_and_the_rest_still_import() {
        when(customerRepo.existingContactsScoped(anyLong(), anyLong(), any()))
                .thenReturn(List.of("0300"));

        ImportReport r = engine.commit(spec, HEADERS + minimal("Ali", "0300") + minimal("Sara", "0301"), ORG, USER);

        assertThat(r.getSkipped()).isEqualTo(1);
        assertThat(r.getToCreate()).isEqualTo(1);
        assertThat(r.getRefused()).as("already existing is not a failure").isZero();
        assertThat(r.isCommitted()).isTrue();
    }

    @Test
    void the_existence_check_is_one_batched_call_not_one_per_row() {
        StringBuilder sb = new StringBuilder(HEADERS);
        for (int i = 0; i < 25; i++) sb.append(minimal("Shop" + i, "030" + i));

        engine.dryRun(spec, sb.toString(), ORG, USER);

        // addCustomer's in-memory full scan per save is exactly what this must not become.
        verify(customerRepo).existingContactsScoped(anyLong(), anyLong(), any());
    }

    @Test
    void the_batched_query_is_asked_about_every_contact_in_the_file() {
        engine.dryRun(spec, HEADERS + minimal("Ali", "0300") + minimal("Sara", "0301"), ORG, USER);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(customerRepo).existingContactsScoped(anyLong(), anyLong(), captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("0300", "0301");
    }

    @Test
    void a_contact_repeated_inside_the_file_is_skipped_not_created_twice() {
        ImportReport r = engine.commit(spec, HEADERS + minimal("Ali", "0300") + minimal("Ali Again", "0300"),
                ORG, USER);

        assertThat(r.getToCreate()).isEqualTo(1);
        assertThat(r.getSkipped()).isEqualTo(1);
        assertThat(r.getRows().get(1).getMessage()).contains("Appears earlier");
    }

    // ── what a built row actually contains ──────────────────────────────────────────────────────────────────

    @Test
    void an_imported_customer_carries_the_file_values_and_the_servers_identity() {
        String csv = HEADERS + "Irfan Medical,03001234567,shop@x.com,Main Bazaar,Lahore,35202-1,DL-9,"
                + "2027-12-31,RETAILER,50000,30\n";

        engine.commit(spec, csv, ORG, USER);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(customerRepo).saveAll(captor.capture());
        Customer c = (Customer) captor.getValue().get(0);

        assertThat(c.getName()).isEqualTo("Irfan Medical");
        assertThat(c.getContact()).isEqualTo("03001234567");
        assertThat(c.getEmail()).isEqualTo("shop@x.com");
        assertThat(c.getCity()).isEqualTo("Lahore");
        assertThat(c.getLicenseNo()).isEqualTo("DL-9");
        assertThat(c.getLicenseExpiry()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(c.getCustomerType()).isEqualTo(CustomerType.RETAILER);
        assertThat(c.getCreditLimit()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(c.getPaymentTermsDays()).isEqualTo(30);

        // Identity and tenancy come from the server, never from the file.
        assertThat(c.getOrganizationId()).isEqualTo(ORG);
        assertThat(c.getUserId()).isEqualTo(USER);
        assertThat(c.getCustomerId()).as("the id is the database's to assign").isNull();
    }

    @Test
    void a_blank_customerType_lands_on_WALK_IN_exactly_as_addCustomer_does() {
        engine.commit(spec, HEADERS + minimal("Ali", "0300"), ORG, USER);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(customerRepo).saveAll(captor.capture());
        Customer c = (Customer) captor.getValue().get(0);

        // "no type" must mean the same thing however the row was created, or every consumer needs its own
        // null rule — the reason V29 backfilled every existing row rather than leaving them NULL.
        assertThat(c.getCustomerType()).isEqualTo(CustomerType.WALK_IN);
    }

    @Test
    void an_imported_customer_starts_with_a_zero_balance_never_null() {
        engine.commit(spec, HEADERS + minimal("Ali", "0300"), ORG, USER);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(customerRepo).saveAll(captor.capture());
        Customer c = (Customer) captor.getValue().get(0);

        assertThat(c.getDueAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void the_whole_batch_is_saved_in_one_call() {
        StringBuilder sb = new StringBuilder(HEADERS);
        for (int i = 0; i < 10; i++) sb.append(minimal("Shop" + i, "030" + i));

        engine.commit(spec, sb.toString(), ORG, USER);

        verify(customerRepo).saveAll(any());
    }

    @Test
    void a_failing_party_bridge_does_not_fail_the_import() {
        // Registering customers predates the party master; an unwired or unavailable party-service must not
        // turn a good import into a failure. The row is imported and left unbridged, which is recorded.
        org.mockito.Mockito.doThrow(new RuntimeException("party-service down"))
                .when(partyBridgeService).bridgeCustomer(any());

        ImportReport r = engine.commit(spec, HEADERS + minimal("Ali", "0300"), ORG, USER);

        assertThat(r.isCommitted()).isTrue();
        assertThat(r.getToCreate()).isEqualTo(1);
    }

    @Test
    void the_duplicate_key_is_contact_not_name() {
        // Two branches of one chain share a name and must both import.
        ImportReport r = engine.commit(spec, HEADERS + minimal("Irfan Medical", "0300")
                + minimal("Irfan Medical", "0301"), ORG, USER);

        assertThat(r.getToCreate()).isEqualTo(2);
        assertThat(r.getSkipped()).isZero();
    }

    @Test
    void the_engine_is_asked_about_the_callers_org_and_user() {
        engine.dryRun(spec, HEADERS + minimal("Ali", "0300"), ORG, USER);

        // Anti-IDOR at the read: the scope comes from the authenticated caller, never from the file.
        verify(customerRepo).existingContactsScoped(org.mockito.ArgumentMatchers.eq(ORG),
                org.mockito.ArgumentMatchers.eq(USER), any());
    }
}
