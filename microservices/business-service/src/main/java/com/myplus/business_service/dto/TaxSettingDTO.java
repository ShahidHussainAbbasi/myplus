package com.myplus.business_service.dto;

import lombok.Data;

import java.math.BigDecimal;

/** Org tax policy I/O (G3 tax engine, slice 35). */
@Data
public class TaxSettingDTO {
    private String taxMode;          // EXCLUSIVE | INCLUSIVE
    private BigDecimal defaultRate;  // %, fallback when a product has no rate
    private String taxLabel;         // printed label, e.g. VAT / GST
    private String taxRegNo;         // printed on the receipt
    private Boolean enabled;
}
