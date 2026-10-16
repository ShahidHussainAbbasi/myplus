package com.myplus.marketplace.multiseller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import jakarta.persistence.Entity;

/**
 * MKT-Xb (R21.2) — every marketplace entity says whose it is. A tenant-owned row carries the organisation(s) it belongs
 * to (organization_id, or the seller / owner / custodian / fulfiller / consignor / consignee org); a row that is
 * MaxTheService's own (R8.1: the customer relationship, the canonical catalogue, the platform's settings) is listed
 * below with the reason; a row that only exists under a scoped parent names that parent. A new entity fails this test
 * until someone decides which it is.
 */
class TenantScopeTest {

    static final String PACKAGE = "com.myplus.marketplace.multiseller.entity";

    /** MaxTheService's own rows: no tenant owns them. */
    static final Map<String, String> PLATFORM = Map.of(
            "MarketplaceCustomer", "the shopper is MaxTheService's customer (R8.1), not a seller's",
            "MarketplaceCustomerSession", "a shopper's login to MaxTheService",
            "MarketplaceOrder", "the shopper's order with MaxTheService; each part (seller order) and line carries its orgs",
            "MarketplacePayment", "the shopper's payment to MaxTheService for an order",
            "MarketplaceProduct", "the canonical catalogue MaxTheService keeps; each seller's source row carries its org",
            "MarketplacePolicy", "warranty, return and commission policies MaxTheService sets",
            "MarketplacePlatformSetting", "MaxTheService's own settings",
            "MarketplaceHoliday", "the settlement calendar's bank holidays");

    /** Rows that only exist under a scoped parent: entity → the parent it is read through. */
    static final Map<String, String> CHILD = Map.of(
            "MarketplaceSupportMessage", "MarketplaceSupportCase (seller_org_id)");

    static boolean scoped(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                String n = f.getName();
                if (f.getType() == Long.class && (n.equals("organizationId") || n.endsWith("OrganizationId") || n.endsWith("OrgId"))) {
                    return true;
                }
            }
        }
        return false;
    }

    static Map<String, Class<?>> entities() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Map<String, Class<?>> out = new TreeMap<>();
        for (BeanDefinition d : scan.findCandidateComponents(PACKAGE)) {
            Class<?> c = Class.forName(d.getBeanClassName());
            out.put(c.getSimpleName(), c);
        }
        return out;
    }

    @Test
    @DisplayName("[MKT-R21.2] every marketplace entity carries its organisation, or is listed as MaxTheService's own or as a child of a scoped row")
    void everyEntityIsScoped() throws Exception {
        Map<String, Class<?>> all = entities();
        assertThat(all).as("the scan found the entities").hasSizeGreaterThanOrEqualTo(27);

        Set<String> unclassified = all.values().stream()
                .filter(c -> !scoped(c) && !PLATFORM.containsKey(c.getSimpleName()) && !CHILD.containsKey(c.getSimpleName()))
                .map(Class::getSimpleName).collect(Collectors.toCollection(TreeSet::new));
        assertThat(unclassified).as("entities with no organisation and no reason").isEmpty();

        // a listed exception must still be true: not scoped after all, and still an entity
        Set<String> stale = new TreeSet<>();
        PLATFORM.keySet().forEach(n -> { if (!all.containsKey(n) || scoped(all.get(n))) stale.add(n); });
        CHILD.keySet().forEach(n -> { if (!all.containsKey(n) || scoped(all.get(n))) stale.add(n); });
        assertThat(stale).as("exceptions that no longer apply").isEmpty();
        CHILD.values().forEach(parent -> {
            String p = parent.substring(0, parent.indexOf(' '));
            assertThat(all.get(p)).as(p).isNotNull();
            assertThat(scoped(all.get(p))).as(p + " is scoped").isTrue();
        });
    }

    @Test
    @DisplayName("[MKT-R21.2] an offer and an order line carry every party's organisation: seller, stock owner, custodian, fulfiller")
    void partiesOnTheRow() throws Exception {
        Map<String, Class<?>> all = entities();
        for (String e : new String[] { "MarketplaceOffer", "MarketplaceOrderLine" }) {
            Set<String> fields = java.util.Arrays.stream(all.get(e).getDeclaredFields()).map(Field::getName).collect(Collectors.toSet());
            assertThat(fields).as(e).contains("sellerOrganizationId", "stockOwnerOrganizationId", "custodianOrganizationId",
                    "fulfillerOrganizationId");
        }
    }
}
