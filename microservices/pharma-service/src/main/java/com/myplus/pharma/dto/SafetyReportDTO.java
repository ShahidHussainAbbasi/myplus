package com.myplus.pharma.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Dispense safety report (P7, slice 44): what the pharmacist must be warned about for a set of items. */
@Data
public class SafetyReportDTO {
    private List<Long> rxRequiredItems = new ArrayList<>();
    private List<Long> controlledItems = new ArrayList<>();
    private List<Interaction> interactions = new ArrayList<>();

    public boolean hasWarnings() { return !controlledItems.isEmpty() || !interactions.isEmpty(); }

    @Data
    public static class Interaction {
        private Long itemId1;
        private Long itemId2;
        private String severity;
        private String description;
        private String recommendation;
    }
}
