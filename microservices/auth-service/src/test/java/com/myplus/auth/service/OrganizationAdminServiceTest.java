package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.myplus.auth.entity.Organization;
import com.myplus.auth.entity.User;
import com.myplus.auth.repository.MembershipRepository;
import com.myplus.auth.repository.OrganizationRepository;
import com.myplus.auth.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * E2 — the operator's tenant view.
 *
 * <p>Design: {@code microservices/docs/slices/e2-operator-portal-design.md}. The Cypress gate proves the
 * screen and the refusals end to end; these pin the two things a screen test cannot see — what the row
 * actually contains, and that a plan change validates.
 */
class OrganizationAdminServiceTest {

    private static Organization org(long id, String name, String plan, LocalDateTime trialEnds) {
        Organization o = new Organization();
        o.setId(id);
        o.setName(name);
        o.setPlan(plan);
        o.setTrialEndsAt(trialEnds);
        o.setOwnerUserId(7L);
        o.setStatus("ACTIVE");
        return o;
    }

    private static User owner() {
        User u = new User();
        u.setId(7L);
        u.setEmail("owner@test.com");
        return u;
    }

    private record Fixture(OrganizationAdminService service, OrganizationRepository orgs) { }

    private static Fixture fixture(List<Organization> rows) {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        MembershipRepository members = mock(MembershipRepository.class);
        UserRepository users = mock(UserRepository.class);

        when(orgs.searchForOperator(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, Pageable.ofSize(25), rows.size()));
        when(orgs.findById(any())).thenAnswer(i ->
                rows.stream().filter(o -> o.getId().equals(i.getArgument(0))).findFirst());
        when(users.findAllById(any())).thenReturn(List.of(owner()));
        when(members.countByOrganizationIds(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{ 1L, 3L }));

        return new Fixture(new OrganizationAdminService(orgs, members, users), orgs);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRow(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("rows")).get(0);
    }

    // ── what the row carries ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ a LAPSED trial is computed SERVER-side, never left to the browser")
    void lapsed_trial_is_computed_here() {
        /*
         * 14 of 20 trials are already past their end date and nothing surfaces it. Deriving "lapsed" in
         * JavaScript from trialEndsAt would be a SECOND definition, competing with the one
         * JpaEntitlementSource uses to decide what the customer may actually do — and the operator would be
         * looking at a different answer from the one being enforced.
         */
        Map<String, Object> lapsed = firstRow(fixture(List.of(
                org(1L, "Lapsed shop", "TRIAL", LocalDateTime.now().minusDays(3)))).service().search(null, 0, 25));
        assertThat(lapsed.get("trialLapsed")).isEqualTo(true);

        Map<String, Object> live = firstRow(fixture(List.of(
                org(1L, "Live trial", "TRIAL", LocalDateTime.now().plusDays(3)))).service().search(null, 0, 25));
        assertThat(live.get("trialLapsed")).isEqualTo(false);
    }

    @Test
    @DisplayName("a PRO tenant with a stale trial date is NOT lapsed")
    void a_paying_tenant_is_never_badged_lapsed() {
        // Otherwise an operator chases a customer who is paying, because a date left over from a previous
        // life is still in the column. Only TRIAL can lapse.
        Map<String, Object> row = firstRow(fixture(List.of(
                org(1L, "Paying shop", "PRO", LocalDateTime.now().minusYears(1)))).service().search(null, 0, 25));
        assertThat(row.get("trialLapsed")).isEqualTo(false);
    }

    @Test
    @DisplayName("the row carries ACCOUNT facts only — never the tenant's trading data")
    void the_row_is_account_facts_only() {
        /*
         * The line Shopify Partners draws and E2 draws in the same place: how much a tenant is TRADING is the
         * tenant's business. A console that shows it becomes a reporting screen on other people's companies,
         * and reaching real tenant data is E5's audited support session.
         *
         * Asserted as an absence, explicitly, because this is the kind of field that gets added later by
         * someone who thinks it would be handy.
         */
        Map<String, Object> row = firstRow(fixture(List.of(
                org(1L, "Shop", "FREE", null))).service().search(null, 0, 25));

        assertThat(row).containsKeys("id", "name", "plan", "trialLapsed", "ownerEmail", "memberCount");
        assertThat(row).doesNotContainKeys("revenue", "orders", "sales", "lastSale", "stockValue");
    }

    @Test
    @DisplayName("member counts come from the bulk count, not a query per row")
    void member_count_is_read_in_bulk() {
        Map<String, Object> row = firstRow(fixture(List.of(
                org(1L, "Shop", "FREE", null))).service().search(null, 0, 25));
        assertThat(row.get("memberCount")).isEqualTo(3);
    }

    // ── search normalisation ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ a blank query becomes '%', never a null parameter")
    void blank_query_becomes_a_wildcard() {
        /*
         * A `:q IS NULL OR …` branch cannot infer the bound parameter's type in Hibernate 6 and fails at
         * RUNTIME on the first call — which a compile-time-clean codebase would ship straight to the gate.
         * One query serves both listing and searching, so the two can never drift into different orderings.
         */
        Fixture f = fixture(List.of(org(1L, "Shop", "FREE", null)));
        f.service().search(null, 0, 25);

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(f.orgs()).searchForOperator(q.capture(), any(Pageable.class));
        assertThat(q.getValue()).isEqualTo("%");
    }

    @Test
    @DisplayName("a search term is lower-cased and wrapped, so the JPQL stays a plain LIKE")
    void search_term_is_normalised() {
        Fixture f = fixture(List.of(org(1L, "Shop", "FREE", null)));
        f.service().search("  MoBiLe ", 0, 25);

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(f.orgs()).searchForOperator(q.capture(), any(Pageable.class));
        assertThat(q.getValue()).isEqualTo("%mobile%");
    }

    @Test
    @DisplayName("the page size is bounded — an operator typo must not become a table scan")
    void page_size_is_bounded() {
        Fixture f = fixture(List.of(org(1L, "Shop", "FREE", null)));
        Map<String, Object> result = f.service().search(null, 0, 100_000);
        assertThat(result.get("size")).isEqualTo(100);

        // And a nonsense page number cannot become a negative offset.
        assertThat(f.service().search(null, -5, 25).get("page")).isEqualTo(0);
    }

    // ── plan changes ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ an unknown plan is REFUSED, not silently resolved to FREE")
    void unknown_plan_is_refused() {
        /*
         * Plan.byCode falls back to FREE for anything unrecognised, which is right for a READ (a licence must
         * not be given away by a typo) and wrong HERE: an operator typing "PLATINUM" would silently put the
         * customer on FREE, quietly narrowing what they may switch on with nothing anywhere saying why.
         *
         * This is the only place an operator writes organizations.plan, so it is where finding F2 — the
         * free-text column — is actually closed.
         */
        OrganizationAdminService svc = fixture(List.of(org(1L, "Shop", "FREE", null))).service();
        assertThatThrownBy(() -> svc.changePlan(1L, "PLATINUM_ULTRA", "because", 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown plan");
    }

    @Test
    @DisplayName("a plan change without a reason is refused")
    void reason_is_required() {
        // A commercial act with no recorded why is unauditable the day somebody asks. Enforced by the API,
        // not by the form: the endpoint is reachable without the screen.
        OrganizationAdminService svc = fixture(List.of(org(1L, "Shop", "FREE", null))).service();
        assertThatThrownBy(() -> svc.changePlan(1L, "PRO", "  ", 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    @DisplayName("moving OFF a trial clears its end date")
    void leaving_trial_clears_the_trial_date() {
        /*
         * Otherwise a paying customer keeps a date in the past and reads as a LAPSED TRIAL on the very screen
         * an operator uses to decide who to chase. The badge would be correct about the column and wrong
         * about the customer.
         */
        Organization o = org(1L, "Upgrading shop", "TRIAL", LocalDateTime.now().minusDays(1));
        OrganizationAdminService svc = fixture(List.of(o)).service();

        assertThatCode(() -> svc.changePlan(1L, "PRO", "upgraded", 9L)).doesNotThrowAnyException();

        assertThat(o.getPlan()).isEqualTo("PRO");
        assertThat(o.getTrialEndsAt()).as("a stale trial date would badge a paying customer as lapsed").isNull();
    }

    @Test
    @DisplayName("a plan change against a missing organization is refused, not silently ignored")
    void missing_organization_is_refused() {
        OrganizationAdminService svc = fixture(List.of(org(1L, "Shop", "FREE", null))).service();
        assertThatThrownBy(() -> svc.changePlan(999L, "PRO", "because", 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No such organization");
    }
}
