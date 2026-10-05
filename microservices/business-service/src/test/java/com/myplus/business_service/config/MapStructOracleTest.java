package com.myplus.business_service.config;

import static com.myplus.business_service.config.MapperProfilesTest.fill;
import static com.myplus.business_service.config.MapperProfilesTest.outcome;
import static com.myplus.business_service.config.MapperProfilesTest.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;

import com.myplus.business_service.dto.*;
import com.myplus.business_service.entity.*;
import com.myplus.business_service.mapper.*;

/**
 * MS — every MapStruct mapper must answer EXACTLY as the MM-1 profile it replaces (the oracle), on the same fully filled
 * fixtures. Design: microservices/docs/slices/ms-1-mapstruct-migration.md §3.
 */
class MapStructOracleTest {

    private final MapperProfiles profiles = new MapperProfiles();

    static <S, T> void sameAsOracle(String what, ModelMapper oracle, Supplier<S> source, Class<T> target, Function<S, T> mapStruct) {
        compare(what, oracle, source, target, mapStruct);
        compare(what + " (empty source)", oracle, () -> emptyLike(source.get()), target, mapStruct);
    }

    /** A fresh instance of the source's class, nothing set: how null fields map. */
    @SuppressWarnings("unchecked")
    static <S> S emptyLike(S filled) {
        try { return (S) filled.getClass().getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    static <S, T> void compare(String what, ModelMapper oracle, Supplier<S> source, Class<T> target, Function<S, T> mapStruct) {
        Object expected = outcome(oracle, source.get(), target);
        assertThat(expected).as("%s: the oracle really maps (a thrown oracle proves nothing)", what).isInstanceOf(java.util.Map.class);
        Object actual;
        try { actual = snapshot(mapStruct.apply(source.get()), 0); }
        catch (RuntimeException e) { actual = "THREW " + e; }
        assertThat(actual).as("%s differs: %s", what, diff(expected, actual)).isEqualTo(decided(expected, actual));
    }

    /**
     * MS-F2 (user decision 2026-10-04): where the oracle shows "now" for a NULL dated/updated, MapStruct shows blank.
     * Only these six fields, only null, and the oracle's side must really be a timestamp; anything else is still a
     * difference.
     */
    static final java.util.Set<String> MS_F2 = java.util.Set.of("CustomerDTO.dated", "CustomerDTO.updated",
            "PurchaseDTO.dated", "PurchaseDTO.updated", "SellDTO.dated", "SellDTO.updated");

    @SuppressWarnings("unchecked")
    static Object decided(Object expected, Object actual) {
        if (!(expected instanceof java.util.Map<?, ?> e) || !(actual instanceof java.util.Map<?, ?> a)) return expected;
        java.util.Map<Object, Object> out = new java.util.LinkedHashMap<>((java.util.Map<Object, Object>) e);
        for (java.util.Map.Entry<?, ?> en : e.entrySet()) {
            Object k = en.getKey(), x = en.getValue(), y = a.get(k);
            if (MS_F2.contains(String.valueOf(k)) && y == null && x != null && String.valueOf(x).matches("\\d{2}-\\d{2}-\\d{4}.*")) out.put(k, null);
            else if (x instanceof java.util.Map && y instanceof java.util.Map) out.put(k, decided(x, y));
        }
        return out;
    }

    /** Only the fields that differ: field = oracle → MapStruct. */
    @SuppressWarnings("unchecked")
    static String diff(Object expected, Object actual) {
        if (!(expected instanceof java.util.Map<?, ?> e) || !(actual instanceof java.util.Map<?, ?> a)) return expected + " → " + actual;
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.Set<Object> keys = new java.util.LinkedHashSet<>(e.keySet());
        keys.addAll(a.keySet());
        for (Object k : keys) {
            Object x = e.get(k), y = a.get(k);
            if (!java.util.Objects.equals(x, y)) out.add(k + " = " + (x instanceof java.util.Map || y instanceof java.util.Map ? diff(x, y) : x + " → " + y));
        }
        return out.toString();
    }

    @Test
    @DisplayName("⭐ the oracle is not blind: a mapper that drops ONE field fails it")
    void oracleSeesADroppedField() {
        StoreMapperImpl real = new StoreMapperImpl();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sameAsOracle("dropped phone", profiles.modelMapper(),
                () -> fill(new Store()), StoreDTO.class, s -> { StoreDTO d = real.toDto(s); d.setPhone(null); return d; }))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("MS-1: Store → StoreDTO equals the plain profile")
    void store() {
        sameAsOracle("Store→StoreDTO", profiles.modelMapper(), () -> fill(new Store()), StoreDTO.class, new StoreMapperImpl()::toDto);
    }

    @Test
    @DisplayName("MS-2: Company, ItemType, ItemUnit, Vender — both directions equal the plain profile")
    void plainPairs() {
        ModelMapper p = profiles.modelMapper();
        CompanyMapperImpl c = new CompanyMapperImpl();
        ItemTypeMapperImpl t = new ItemTypeMapperImpl();
        ItemUnitMapperImpl u = new ItemUnitMapperImpl();
        VenderMapperImpl v = new VenderMapperImpl();
        org.assertj.core.api.SoftAssertions soft = new org.assertj.core.api.SoftAssertions();
        soft.assertThatCode(() -> sameAsOracle("Company→DTO", p, () -> fill(new Company()), CompanyDTO.class, c::toDto)).doesNotThrowAnyException();
        soft.assertThatCode(() -> sameAsOracle("DTO→Company", p, () -> fill(new CompanyDTO()), Company.class, c::toEntity)).doesNotThrowAnyException();
        soft.assertThatCode(() -> sameAsOracle("ItemType→DTO", p, () -> fill(new ItemType()), ItemTypeDTO.class, t::toDto)).doesNotThrowAnyException();
        soft.assertThatCode(() -> sameAsOracle("DTO→ItemType", p, () -> fill(new ItemTypeDTO()), ItemType.class, t::toEntity)).doesNotThrowAnyException();
        soft.assertThatCode(() -> sameAsOracle("ItemUnit→DTO", p, () -> fill(new ItemUnit()), ItemUnitDTO.class, u::toDto)).doesNotThrowAnyException();
        soft.assertThatCode(() -> sameAsOracle("DTO→ItemUnit", p, () -> fill(new ItemUnitDTO()), ItemUnit.class, u::toEntity)).doesNotThrowAnyException();
        soft.assertThatCode(() -> sameAsOracle("Vender→DTO", p, () -> fill(new Vender()), VenderDTO.class, v::toDto)).doesNotThrowAnyException();
        soft.assertThatCode(() -> sameAsOracle("DTO→Vender", p, () -> fill(new VenderDTO()), Vender.class, v::toEntity)).doesNotThrowAnyException();
        soft.assertAll();
    }

    @Test
    @DisplayName("MS-3: Customer both ways and Purchase → DTO equal the display profile (warm Customer / Purchase screens)")
    void displayPairs() {
        LegacyConverters appUtil = new LegacyConverters();
        ModelMapper d = profiles.displayMapper(appUtil);
        CustomerMapperImpl c = new CustomerMapperImpl();
        PurchaseMapperImpl pu = new PurchaseMapperImpl();
        org.assertj.core.api.SoftAssertions soft = new org.assertj.core.api.SoftAssertions();
        soft.assertThatCode(() -> sameAsOracle("Customer→DTO", d, () -> fill(new Customer()), CustomerDTO.class, c::toDto)).doesNotThrowAnyException();
        soft.assertThatCode(() -> sameAsOracle("DTO→Customer", d, () -> fill(new CustomerDTO()), Customer.class, c::toEntity)).doesNotThrowAnyException();
        soft.assertThatCode(() -> sameAsOracle("Purchase→DTO", d, () -> withoutStockEntryId(fill(new Purchase())), PurchaseDTO.class, pu::toDto)).doesNotThrowAnyException();
        soft.assertAll();
    }

    @Test
    @DisplayName("MS-4: the sale screens — Sell (with its header and buyer), CustomerHistory, Customer — equal the STRICT profile")
    void saleScreens() {
        ModelMapper strict = profiles.saleDisplayMapper(new LegacyConverters());
        SaleScreenMapperImpl m = new SaleScreenMapperImpl();
        org.assertj.core.api.SoftAssertions soft = new org.assertj.core.api.SoftAssertions();
        soft.assertThatCode(() -> sameAsOracle("Sell→DTO", strict, () -> fill(new Sell()), SellDTO.class, (Sell x) -> m.toDto(x))).doesNotThrowAnyException();
        soft.assertThatCode(() -> sameAsOracle("CustomerHistory→DTO", strict, () -> fill(new CustomerHistory()), CustomerHistoryDTO.class, (CustomerHistory x) -> m.toDto(x))).doesNotThrowAnyException();
        soft.assertThatCode(() -> sameAsOracle("Customer→DTO (sale)", strict, () -> fill(new Customer()), CustomerDTO.class, (Customer x) -> m.toDto(x))).doesNotThrowAnyException();
        soft.assertAll();
    }

    @Test
    @DisplayName("MS-5: a purchase being saved — PurchaseDTO → Purchase equals the purchaseInput profile (typed and blank dates)")
    void purchaseInput() {
        ModelMapper in = profiles.purchaseInputMapper(new LegacyConverters());
        PurchaseInputMapperImpl m = new PurchaseInputMapperImpl();
        // The fixture really carries the posted stock.* values, and the oracle really leaves the entity's b* fields
        // empty — PurchaseService copies them from dto.getStock() itself — so "ignored" is the oracle's own answer,
        // not a blind spot.
        PurchaseDTO probe = fill(new PurchaseDTO());
        assertThat(probe.getStock()).isNotNull();
        assertThat(probe.getStock().getBpurchaseRate()).isNotNull();
        assertThat(in.map(fill(new PurchaseDTO()), Purchase.class).getBpurchaseRate()).as("ModelMapper did not deep-map stock.*").isNull();
        sameAsOracle("DTO→Purchase", in, () -> fill(new PurchaseDTO()), Purchase.class, m::toEntity);
        sameAsOracle("DTO→Purchase (blank dates)", in, () -> blank(fill(new PurchaseDTO())), Purchase.class, m::toEntity);
    }

    /** Every date-like String blank, as an untouched date picker posts it — in the DTO and its nested stock. */
    static <T> T blank(T o) {
        for (Object target : new Object[] { o, nested(o, "stock") }) {
            if (target == null) continue;
            for (Class<?> c = target.getClass(); c != null && c.getName().startsWith("com.myplus"); c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() == String.class && f.getName().toLowerCase().matches(".*(date|dated|updated)$")) {
                        f.setAccessible(true);
                        try { f.set(target, ""); } catch (IllegalAccessException ignored) { }
                    }
                }
            }
        }
        return o;
    }

    static Object nested(Object o, String field) {
        try { java.lang.reflect.Field f = o.getClass().getDeclaredField(field); f.setAccessible(true); return f.get(o); }
        catch (ReflectiveOperationException e) { return null; }
    }

    @Test
    @DisplayName("⭐ MS-F2: a record with no time shows BLANK — the old mapper invented the current time")
    void nullDatesAreBlank() {
        LegacyConverters appUtil = new LegacyConverters();
        PurchaseDTO oldWay = profiles.displayMapper(appUtil).map(new Purchase(), PurchaseDTO.class);
        assertThat(oldWay.getUpdated()).as("the old mapper: a purchase with no updated time showed NOW").isNotNull();
        PurchaseDTO p = new PurchaseMapperImpl().toDto(new Purchase());
        assertThat(p.getDated()).isNull();
        assertThat(p.getUpdated()).isNull();
        CustomerDTO c = new CustomerMapperImpl().toDto(new Customer());
        assertThat(c.getDated()).isNull();
        assertThat(c.getUpdated()).isNull();
        SellDTO s = new SaleScreenMapperImpl().toDto(new Sell());
        assertThat(s.getDated()).isNull();
        assertThat(s.getUpdated()).isNull();
        // a time that IS recorded still shows, in the screen format
        Purchase withTime = new Purchase();
        withTime.setUpdated(java.time.LocalDateTime.of(2026, 10, 4, 9, 5, 7));
        assertThat(new PurchaseMapperImpl().toDto(withTime).getUpdated()).isEqualTo("04-10-2026 09:05:07");
    }

    @Test
    @DisplayName("MS-6: the sale header from the posted customer — equals ObjectMapperUtils' STRICT ModelMapper")
    void saleHeader() {
        ModelMapper strictPlain = new ModelMapper();
        strictPlain.getConfiguration().setMatchingStrategy(org.modelmapper.convention.MatchingStrategies.STRICT);
        sameAsOracle("CustomerDTO→CustomerHistory", strictPlain, () -> fill(new CustomerDTO()), CustomerHistory.class,
                new SaleHeaderMapperImpl()::fromCustomer);
    }

    /**
     * PR-3b — Purchase.stockEntryId is NEWER than the ModelMapper code this oracle stands for, so the old behaviour has
     * no such field. Left filled, STANDARD matching fuzzes it onto PurchaseDTO.stock.stockId and invents a stock object
     * the old screens never showed; MapStruct (production) rightly leaves stock null. Cleared here, not ignored there.
     */
    private static Purchase withoutStockEntryId(Purchase p) {
        p.setStockEntryId(null);
        return p;
    }
}
