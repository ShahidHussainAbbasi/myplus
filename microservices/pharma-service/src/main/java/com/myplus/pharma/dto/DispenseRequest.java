package com.myplus.pharma.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Dispense request (P6, slice 43): the trade sale invoice that fulfilled it + the dispensed lines (itemId, qty). */
@Data
public class DispenseRequest {
    private String invoiceNo;
    private List<Line> items = new ArrayList<>();

    @Data
    public static class Line {
        private Long itemId;
        private int quantity;
    }
}
