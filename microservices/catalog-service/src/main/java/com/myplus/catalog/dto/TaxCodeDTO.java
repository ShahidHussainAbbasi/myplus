package com.myplus.catalog.dto;

import lombok.*;

import java.math.BigDecimal;

/** Multi-rate tax: a tax-code master row for the management screen + the product-form dropdown. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TaxCodeDTO {
    private Long id;
    private String name;
    private BigDecimal rate;
    private Boolean isDefault;
    private Boolean active;
}
