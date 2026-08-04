package com.myplus.education.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StudentDTO {
    /** Slice 0.2b: fee credit the school holds for this student. Read-only here — the ledger owns it. Without
     *  this the fee screens cannot show a guardian the money already paid in, which is the point of holding it. */
    private java.math.BigDecimal creditBalance;

    private Long id;
    private Long userId;
    private String name;
    private String enrollNo;
    private String enrollDateStr;
    private String ysStr;
    private String yeStr;
    private String feeMode;
    private String email;
    private String mobile;
    private Long partyId;   // P3: shared party/contact master id
    private String address;
    private String dateOfBirthStr;
    private String gender;
    private String bloodGroup;
    private String status;
    private Long schoolId;
    private String schoolName;
    private Long guardianId;
    private String guardianName;
    private Long gradeId;
    private String gradeName;
    private Long vehicleId;
    private Long discountId;
    private Integer nd;
    private String datedStr;
    private String updatedStr;
}
