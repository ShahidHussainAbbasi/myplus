package com.myplus.business_service.config;

import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.myplus.business_service.dto.ItemDTO;
import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.dto.StockDTO;
import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.entity.Sell;
import com.myplus.business_service.entity.Stock;
import com.myplus.business_service.util.AppUtil;

/**
 * Single shared {@link ModelMapper} bean (tech-debt: "new ModelMapper() per controller").
 *
 * <p>Replaces the 13 per-component {@code new ModelMapper()} instances + their per-request
 * {@code addConverter(...)} mutations. The old pattern mutated a shared converter map at request time,
 * which is unsafe on a singleton (the last {@code addConverter} for a given Java type-pair wins, so one
 * request could change date parsing for another). Here every date field is wired explicitly inside a
 * {@link org.modelmapper.TypeMap} via {@code using(converter)}, so the converter is scoped to a property
 * within a specific (source,dest) pair — no global collisions — and the same mapper is reused everywhere.
 *
 * <p>Matching strategy is STRICT (only exact-name matches map; never guesses). Previously only
 * SellController used STRICT; the other sites used the default STANDARD. STRICT is the conservative
 * choice and matches the most complex (nested) mappings.
 *
 * <p>Empty/null date semantics are preserved exactly from the original converters:
 * <ul>
 *   <li>Output (entity→DTO): {@code localDate(Time)ToString} → empty source becomes "now" formatted.</li>
 *   <li>Input default: plain {@code stringToLocalDate(Time)} → empty becomes "now". Used by Item/Sell.</li>
 *   <li>Stock/Purchase input uses the IGNORE variants → empty becomes {@code null} (so an unset
 *       mfg/expiry date is not silently set to today). Both live StockService map sites used the
 *       IGNORE converter; the only plain (now-default) Stock site was dead/commented code.</li>
 * </ul>
 */
@Configuration
public class ModelMapperConfig {

    private final AppUtil appUtil;

    public ModelMapperConfig(AppUtil appUtil) {
        this.appUtil = appUtil;
    }

    @Bean
    public ModelMapper modelMapper() {
        ModelMapper mm = new ModelMapper();
        mm.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);

        // ---------- OUTPUT: entity -> DTO (LocalDate/LocalDateTime -> String) ----------
        mm.createTypeMap(Customer.class, CustomerDTO.class).addMappings(m -> {
            m.using(appUtil.localDateTimeToString).map(Customer::getDated,   CustomerDTO::setDated);
            m.using(appUtil.localDateTimeToString).map(Customer::getUpdated, CustomerDTO::setUpdated);
        });

        mm.createTypeMap(Item.class, ItemDTO.class).addMappings(m -> {
            m.using(appUtil.localDateTimeToString).map(Item::getDated,   ItemDTO::setDated);
            m.using(appUtil.localDateTimeToString).map(Item::getUpdated, ItemDTO::setUpdated);
        });

        mm.createTypeMap(Purchase.class, PurchaseDTO.class).addMappings(m -> {
            m.using(appUtil.localDateTimeToString).map(Purchase::getDated,   PurchaseDTO::setDated);
            m.using(appUtil.localDateToString)    .map(Purchase::getUpdated, PurchaseDTO::setUpdated);
        });

        mm.createTypeMap(Stock.class, StockDTO.class).addMappings(m -> {
            m.using(appUtil.localDateToString).map(Stock::getBmfgDate, StockDTO::setBmfgDate);
            m.using(appUtil.localDateToString).map(Stock::getBexpDate, StockDTO::setBexpDate);
            m.using(appUtil.localDateToString).map(Stock::getDated,    StockDTO::setDated);
            m.using(appUtil.localDateToString).map(Stock::getUpdated,  StockDTO::setUpdated);
        });

        mm.createTypeMap(Sell.class, SellDTO.class).addMappings(m -> {
            m.using(appUtil.localDateTimeToString).map(Sell::getDated,   SellDTO::setDated);
            m.using(appUtil.localDateTimeToString).map(Sell::getUpdated, SellDTO::setUpdated);
        });

        // ---------- INPUT: DTO -> entity (String -> LocalDate/LocalDateTime) ----------
        // Item / Sell: plain converter (empty -> now). Controller paths overwrite dates manually anyway.
        mm.createTypeMap(ItemDTO.class, Item.class).addMappings(m -> {
            m.using(appUtil.stringToLocalDateTime).map(ItemDTO::getDated,   Item::setDated);
            m.using(appUtil.stringToLocalDateTime).map(ItemDTO::getUpdated, Item::setUpdated);
        });

        mm.createTypeMap(SellDTO.class, Sell.class).addMappings(m -> {
            m.using(appUtil.stringToLocalDateTime).map(SellDTO::getDated,   Sell::setDated);
            m.using(appUtil.stringToLocalDateTime).map(SellDTO::getUpdated, Sell::setUpdated);
        });

        // Purchase: IGNORE variant (empty -> null). Note Purchase.updated is a LocalDate.
        mm.createTypeMap(PurchaseDTO.class, Purchase.class).addMappings(m -> {
            m.using(appUtil.stringToLocalDateTimeIgnoreEmptyOrNull).map(PurchaseDTO::getDated,   Purchase::setDated);
            m.using(appUtil.stringToLocalDateIgnoreEmptyOrNull)    .map(PurchaseDTO::getUpdated, Purchase::setUpdated);
        });

        // Stock: IGNORE variant — empty mfg/expiry/dated/updated -> null (all LocalDate). Both live
        // StockService map sites used this; the plain (now-default) site was dead/commented code.
        mm.createTypeMap(StockDTO.class, Stock.class).addMappings(m -> {
            m.using(appUtil.stringToLocalDateIgnoreEmptyOrNull).map(StockDTO::getBmfgDate, Stock::setBmfgDate);
            m.using(appUtil.stringToLocalDateIgnoreEmptyOrNull).map(StockDTO::getBexpDate, Stock::setBexpDate);
            m.using(appUtil.stringToLocalDateIgnoreEmptyOrNull).map(StockDTO::getDated,    Stock::setDated);
            m.using(appUtil.stringToLocalDateIgnoreEmptyOrNull).map(StockDTO::getUpdated,  Stock::setUpdated);
        });

        return mm;
    }
}
