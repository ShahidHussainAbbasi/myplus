package com.myplus.business_service.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.math.BigDecimal;

/** Park request (POS R10, slice 40). {@code cart} is the checkout payload to store and rebuild on resume. */
@Data
public class ParkSaleDTO {
    private String label;
    private Integer itemCount;
    private BigDecimal total;
    private JsonNode cart;     // the customerHistory payload (lines + customer + tenders), stored verbatim
}
