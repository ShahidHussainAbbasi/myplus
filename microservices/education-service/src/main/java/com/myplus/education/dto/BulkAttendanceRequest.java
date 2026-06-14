package com.myplus.education.dto;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Payload for marking a whole class roster in one request (slice 13). */
@Data
@NoArgsConstructor
public class BulkAttendanceRequest {
    private Long gradeId;
    private String dateStr;          // dd-MM-yyyy
    private List<Row> rows;

    @Data
    @NoArgsConstructor
    public static class Row {
        private String enrollNo;
        private String status;       // Present | Absent | Late
        private String timeInStr;
        private String timeOutStr;
        private String remark;
    }
}
