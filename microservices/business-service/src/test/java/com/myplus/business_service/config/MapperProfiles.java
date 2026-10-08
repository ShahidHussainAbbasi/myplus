package com.myplus.business_service.config;

import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;


/**
 * MS-6 — now TEST-ONLY: the frozen oracle MapStructOracleTest characterizes the MapStruct mappers against. Not a Spring
 * configuration any more (production has no ModelMapper). Below, the MM-1 description as it was.
 *
 * MM-1 — every ModelMapper in business-service, configured ONCE here and only mapped with afterwards.
 * Design: microservices/docs/slices/mm-1-mapper-profiles.md.
 *
 * <h3>The defect this ends</h3>
 * Controllers kept their own mapper and added the date converters INSIDE request handlers. So a mapper's output depended
 * on which request reached it first after a deploy ({@code getAllPurchase} printed {@code 2026-10-04T10:20:30} cold and
 * {@code 04-10-2026 10:20:30} warm), and its configuration changed while other threads were mapping with it.
 *
 * <h3>Each profile is the WARM state of the class that used it</h3>
 * Same matching strategy, same converters. {@code MapperProfilesTest} rebuilds every old mapper the old way and asserts the
 * output is identical. ⚠ Do NOT fold these into one bean, and do NOT make the plain one STRICT: STRICT silently drops
 * fields STANDARD matches (nested {@code stock.*}, {@code Customer.getId()} → {@code customerId}), which is why
 * {@code refactor/modelmapper-typemaps} was not merged.
 */
public class MapperProfiles {

    public static final String DISPLAY = "displayMapper";
    public static final String SALE_DISPLAY = "saleDisplayMapper";
    public static final String PURCHASE_INPUT = "purchaseInputMapper";

    /** STANDARD, no converters: Company, ItemType, ItemUnit, Store, Vender. */
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }

    /** STANDARD + LocalDate/LocalDateTime → "dd-MM-yyyy[ HH:mm:ss]": the Customer and Purchase screens. */
    public ModelMapper displayMapper(LegacyConverters appUtil) {
        ModelMapper m = new ModelMapper();
        m.addConverter(appUtil.localDateToString);
        m.addConverter(appUtil.localDateTimeToString);
        return m;
    }

    /** STRICT + the same display converters: the sale screens (SellController was STRICT on its own). */
    public ModelMapper saleDisplayMapper(LegacyConverters appUtil) {
        ModelMapper m = new ModelMapper();
        m.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
        m.addConverter(appUtil.localDateTimeToString);
        m.addConverter(appUtil.localDateToString);
        return m;
    }

    /** STANDARD + "dd-MM-yyyy[ HH:mm:ss]" → LocalDate/LocalDateTime, blank → null: a purchase being saved. */
    public ModelMapper purchaseInputMapper(LegacyConverters appUtil) {
        ModelMapper m = new ModelMapper();
        m.addConverter(appUtil.stringToLocalDateTimeIgnoreEmptyOrNull);
        m.addConverter(appUtil.stringToLocalDateIgnoreEmptyOrNull);
        return m;
    }
}
