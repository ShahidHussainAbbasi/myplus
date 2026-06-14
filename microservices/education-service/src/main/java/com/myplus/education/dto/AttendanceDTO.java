package com.myplus.education.dto;

import java.time.LocalDateTime;
import java.time.LocalTime;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AttendanceDTO {
    private Long id;
    private Long userId;
    private String dtStr;
    private String en;
    private String sn;
    private Long grid;
    private String g;
    private String gn;
    private String status;
    private LocalDateTime dt;
    private LocalTime in;
    private LocalTime out;
    private String rem;
}
