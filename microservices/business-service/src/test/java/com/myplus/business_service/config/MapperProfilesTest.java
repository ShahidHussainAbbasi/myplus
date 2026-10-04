package com.myplus.business_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;

import com.myplus.business_service.dto.*;
import com.myplus.business_service.entity.*;

/**
 * MM-1 — characterization: every profile maps EXACTLY as the old per-class mapper did once warm.
 * Design: microservices/docs/slices/mm-1-mapper-profiles.md.
 *
 * <p>Each "old" mapper is rebuilt the way its class used to end up after its first request (strategy + the converters
 * the handlers added, in their order). Fixtures fill every field, nested business objects included, so a field the new
 * profile maps differently, or drops, fails here.
 */
class MapperProfilesTest {

    private final LegacyConverters appUtil = new LegacyConverters();
    private final MapperProfiles profiles = new MapperProfiles();

    // ── the old mappers, rebuilt as the old code left them WARM ─────────────────────────────────────────────────────
    private ModelMapper oldPlain() { return new ModelMapper(); }

    /** CustomerController: LocalDate first, then LocalDateTime. */
    private ModelMapper oldCustomer() {
        ModelMapper m = new ModelMapper();
        m.addConverter(appUtil.localDateToString);
        m.addConverter(appUtil.localDateTimeToString);
        return m;
    }

    /** PurchaseController: LocalDateTime first, then LocalDate (the reverse order). */
    private ModelMapper oldPurchaseScreen() {
        ModelMapper m = new ModelMapper();
        m.addConverter(appUtil.localDateTimeToString);
        m.addConverter(appUtil.localDateToString);
        return m;
    }

    /** SellController: STRICT from its initializer, then the converters its handlers added. */
    private ModelMapper oldSell() {
        ModelMapper m = new ModelMapper();
        m.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
        m.addConverter(appUtil.localDateTimeToString);
        m.addConverter(appUtil.localDateToString);
        return m;
    }

    /** PurchaseService.doAddPurchase / updatePurchase. */
    private ModelMapper oldPurchaseInput() {
        ModelMapper m = new ModelMapper();
        m.addConverter(appUtil.stringToLocalDateTimeIgnoreEmptyOrNull);
        m.addConverter(appUtil.stringToLocalDateIgnoreEmptyOrNull);
        return m;
    }

    // ── the comparisons ────────────────────────────────────────────────────────────────────────────────────────────
    private <S> void same(String what, ModelMapper oldM, ModelMapper newM, Supplier<S> source, Class<?> target) {
        Object before = outcome(oldM, source.get(), target);
        Object after = outcome(newM, source.get(), target);
        assertThat(after).as(what).isEqualTo(before);
    }

    @Test
    @DisplayName("plain profile = the old plain mappers: Company, ItemType, ItemUnit, Store, Vender — both directions")
    void plain() {
        ModelMapper p = profiles.modelMapper();
        same("Company→DTO", oldPlain(), p, () -> fill(new Company()), CompanyDTO.class);
        same("DTO→Company", oldPlain(), p, () -> fill(new CompanyDTO()), Company.class);
        same("ItemType→DTO", oldPlain(), p, () -> fill(new ItemType()), ItemTypeDTO.class);
        same("DTO→ItemType", oldPlain(), p, () -> fill(new ItemTypeDTO()), ItemType.class);
        same("ItemUnit→DTO", oldPlain(), p, () -> fill(new ItemUnit()), ItemUnitDTO.class);
        same("DTO→ItemUnit", oldPlain(), p, () -> fill(new ItemUnitDTO()), ItemUnit.class);
        same("Store→DTO", oldPlain(), p, () -> fill(new Store()), StoreDTO.class);
        same("Vender→DTO", oldPlain(), p, () -> fill(new Vender()), VenderDTO.class);
        same("DTO→Vender", oldPlain(), p, () -> fill(new VenderDTO()), Vender.class);
    }

    @Test
    @DisplayName("display profile = warm CustomerController AND warm PurchaseController (converter order does not matter)")
    void display() {
        ModelMapper d = profiles.displayMapper(appUtil);
        same("Customer→DTO", oldCustomer(), d, () -> fill(new Customer()), CustomerDTO.class);
        same("DTO→Customer", oldCustomer(), d, () -> fill(new CustomerDTO()), Customer.class);
        same("Purchase→DTO", oldPurchaseScreen(), d, () -> fill(new Purchase()), PurchaseDTO.class);
    }

    @Test
    @DisplayName("sale display profile = warm SellController (STRICT): Sell, CustomerHistory, Customer")
    void saleDisplay() {
        ModelMapper s = profiles.saleDisplayMapper(appUtil);
        same("Sell→DTO", oldSell(), s, () -> fill(new Sell()), SellDTO.class);
        same("CustomerHistory→DTO", oldSell(), s, () -> fill(new CustomerHistory()), CustomerHistoryDTO.class);
        same("Customer→DTO", oldSell(), s, () -> fill(new Customer()), CustomerDTO.class);
    }

    @Test
    @DisplayName("purchase input profile = PurchaseService: typed dates parse, blank dates stay null")
    void purchaseInput() {
        ModelMapper i = profiles.purchaseInputMapper(appUtil);
        same("DTO→Purchase", oldPurchaseInput(), i, () -> fill(new PurchaseDTO()), Purchase.class);
        same("DTO→Purchase (blank dates)", oldPurchaseInput(), i, () -> blankDates(fill(new PurchaseDTO())), Purchase.class);
    }

    @Test
    @DisplayName("⭐ the defect: a COLD controller mapper printed dates differently; the profile is always the warm answer")
    void coldWasDifferent() {
        Object cold = outcome(oldPlain(), fill(new Purchase()), PurchaseDTO.class);      // getAllPurchase before getUserPurchase
        Object warm = outcome(oldPurchaseScreen(), fill(new Purchase()), PurchaseDTO.class);
        assertThat(cold).as("the old cold answer really differed").isNotEqualTo(warm);
        assertThat(outcome(profiles.displayMapper(appUtil), fill(new Purchase()), PurchaseDTO.class)).isEqualTo(warm);
    }

    @Test
    @DisplayName("⭐ the fixtures can SEE a wrong profile: STRICT≠STANDARD on Sell, display≠plain on Customer, nothing threw")
    void testCanSeeDifferences() {
        ModelMapper standardWithConverters = oldPurchaseScreen();
        assertThat(outcome(oldSell(), fill(new Sell()), SellDTO.class))
                .as("a STANDARD mapper would answer differently for the sale — so the STRICT profile is really checked")
                .isNotEqualTo(outcome(standardWithConverters, fill(new Sell()), SellDTO.class));
        assertThat(outcome(oldCustomer(), fill(new Customer()), CustomerDTO.class))
                .isNotEqualTo(outcome(oldPlain(), fill(new Customer()), CustomerDTO.class));
        Object[][] pairs = {
                { oldPlain(), new Company(), CompanyDTO.class }, { oldPlain(), new CompanyDTO(), Company.class },
                { oldPlain(), new ItemType(), ItemTypeDTO.class }, { oldPlain(), new ItemTypeDTO(), ItemType.class },
                { oldPlain(), new ItemUnit(), ItemUnitDTO.class }, { oldPlain(), new ItemUnitDTO(), ItemUnit.class },
                { oldPlain(), new Store(), StoreDTO.class }, { oldPlain(), new Vender(), VenderDTO.class },
                { oldPlain(), new VenderDTO(), Vender.class }, { oldCustomer(), new Customer(), CustomerDTO.class },
                { oldCustomer(), new CustomerDTO(), Customer.class }, { oldPurchaseScreen(), new Purchase(), PurchaseDTO.class },
                { oldSell(), new Sell(), SellDTO.class }, { oldSell(), new CustomerHistory(), CustomerHistoryDTO.class },
                { oldPurchaseInput(), new PurchaseDTO(), Purchase.class } };
        for (Object[] p : pairs) {
            Object out = outcome((ModelMapper) p[0], fill(p[1]), (Class<?>) p[2]);
            assertThat(out).as("%s → %s really maps", p[1].getClass().getSimpleName(), ((Class<?>) p[2]).getSimpleName())
                    .isInstanceOf(Map.class);
        }
    }

    @Test
    @DisplayName("mapping never changes a profile: 50 maps later the answer is the same as the first")
    void profilesAreNotMutated() {
        ModelMapper d = profiles.displayMapper(appUtil);
        Object first = outcome(d, fill(new Customer()), CustomerDTO.class);
        for (int i = 0; i < 50; i++) d.map(fill(new Customer()), CustomerDTO.class);
        assertThat(outcome(d, fill(new Customer()), CustomerDTO.class)).isEqualTo(first);
        assertThat(d.getTypeMaps()).as("no type map added by mapping itself beyond the first use").hasSizeLessThanOrEqualTo(
                profiles.displayMapper(appUtil).getTypeMaps().size() + 1);
    }

    // ── fixtures and snapshots ─────────────────────────────────────────────────────────────────────────────────────
    private static final LocalDate D = LocalDate.of(2026, 10, 4);
    private static final LocalDateTime DT = LocalDateTime.of(2026, 10, 4, 10, 20, 30);

    /** Every settable field gets a distinct value; nested business objects are filled one level deep. */
    static <T> T fill(T o) { return fill(o, 0); }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static <T> T fill(T o, int depth) {
        for (Class<?> c = o.getClass(); c != null && c.getName().startsWith("com.myplus"); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) continue;
                f.setAccessible(true);
                Class<?> t = f.getType();
                Object v = null;
                String n = f.getName();
                if (t == String.class) v = n.toLowerCase().matches(".*(date|dated|updated|time|on)$") ? "04-10-2026" : "s-" + n;
                else if (t == Long.class || t == long.class) v = (long) (n.hashCode() & 0xffff) + 1;
                else if (t == Integer.class || t == int.class) v = (n.hashCode() & 0xff) + 1;
                else if (t == Double.class || t == double.class) v = 3.5d;
                else if (t == Float.class || t == float.class) v = 2.5f;
                else if (t == Short.class || t == short.class) v = (short) 3;
                else if (t == Boolean.class || t == boolean.class) v = Boolean.TRUE;
                else if (t == BigDecimal.class) v = new BigDecimal("12.34");
                else if (t == LocalDate.class) v = D;
                else if (t == LocalDateTime.class) v = DT;
                else if (t.isEnum()) v = t.getEnumConstants().length > 0 ? t.getEnumConstants()[0] : null;
                else if (depth == 0 && t.getName().startsWith("com.myplus.business_service")
                        && !t.isInterface() && !Modifier.isAbstract(t.getModifiers())) {
                    try { v = fill(t.getDeclaredConstructor().newInstance(), depth + 1); } catch (ReflectiveOperationException e) { v = null; }
                }
                if (v == null) continue;
                try { f.set(o, v); } catch (IllegalAccessException ignored) { }
            }
        }
        return o;
    }

    private static PurchaseDTO blankDates(PurchaseDTO d) {
        for (Class<?> c = d.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == String.class && f.getName().toLowerCase().matches(".*(date|dated|updated)$")) {
                    f.setAccessible(true);
                    try { f.set(d, ""); } catch (IllegalAccessException ignored) { }
                }
            }
        }
        return d;
    }

    /** The mapped result as comparable values — or the exception, which must ALSO be the same. */
    static Object outcome(ModelMapper m, Object source, Class<?> target) {
        try {
            return snapshot(m.map(source, target), 0);
        } catch (RuntimeException e) {
            return "THREW " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse("");
        }
    }

    static Object snapshot(Object o, int depth) {
        if (o == null) return null;
        Class<?> c0 = o.getClass();
        if (c0.isEnum() || o instanceof Enum<?> || !c0.getName().startsWith("com.myplus") || depth > 2) {
            return o instanceof Collection<?> col ? "size=" + col.size() : String.valueOf(o);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Class<?> c = c0; c != null && c.getName().startsWith("com.myplus"); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try { m.put(c.getSimpleName() + "." + f.getName(), snapshot(f.get(o), depth + 1)); }
                catch (IllegalAccessException e) { m.put(f.getName(), "?"); }
            }
        }
        return m;
    }
}
