package com.myplus.pharma.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** HMS H3b — one change, as the pharmacy screen and the doctor's visit show it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmendmentView {
    private Long id;
    private String kind;
    private String reason;
    private Long amendedBy;
    private LocalDateTime amendedAt;
    private List<Map<String, Object>> changes;
}
