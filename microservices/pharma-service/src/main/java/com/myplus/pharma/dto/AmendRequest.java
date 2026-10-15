package com.myplus.pharma.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * HMS H3b — the pharmacist's change to a prescription: the lines as they should be now (an existing line keeps its
 * {@code id}; a new line has none; a line left out is removed), a reason, and how many changes the screen had seen
 * (a stale screen is refused instead of overwriting another pharmacist's change).
 */
@Data
public class AmendRequest {
    private String reason;
    private Long amendmentsSeen;
    private List<PrescriptionItemDTO> items = new ArrayList<>();
}
