package com.myplus.education.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public class EducationDTOs {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SchoolDTO {
        private Long id;
        private String name;
        private Long userId;
        private String email;
        private String phone;
        private String address;
        private String branchName;
        private String status;
        private LocalDateTime dated;
        private LocalDateTime updated;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OwnerDTO {
        private Long id;
        private Long userId;
        private String name;
        private String email;
        private String mobile;
        private String address;
        private String status;
        private LocalDateTime dated;
        private LocalDateTime updated;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GradeDTO {
        private Long id;
        private String name;
        private Long userId;
        private String code;
        private String section;
        private LocalTime timeFrom;
        private LocalTime timeTo;
        private String status;
        private Long schoolId;
        private Float fee;
        private Long room;
        private LocalDateTime dated;
        private LocalDateTime updated;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GuardianDTO {
        private Long id;
        private String name;
        private Long userId;
        private String email;
        private String mobile;
        private String phone;
        private String tempAddress;
        private String permAddress;
        private String gender;
        private String relation;
        private String occupation;
        private String status;
        private String cnic;
        private LocalDateTime dated;
        private LocalDateTime updated;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StudentDTO {
        private Long id;
        private String name;
        private Long userId;
        private LocalDate ys;
        private LocalDate ye;
        private String enrollNo;
        private LocalDate enrollDate;
        private String feeMode;
        private String email;
        private String mobile;
        private String address;
        private LocalDate dateOfBirth;
        private String gender;
        private String bloodGroup;
        private String status;
        private Long schoolId;
        private Long guardianId;
        private Long gradeId;
        private Long vehicleId;
        private Long discountId;
        private Integer nd;
        private String di;
        private Float fee;
        private Integer dueDay;
        private Integer vf;
        private String pob;
        private String mn;
        private String wa;
        private String religion;
        private LocalDateTime dated;
        private LocalDateTime updated;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StaffDTO {
        private Long id;
        private String name;
        private Long userId;
        private String email;
        private String mobile;
        private String phone;
        private String address;
        private String designation;
        private LocalDate staffDOB;
        private String gender;
        private LocalTime timeIn;
        private LocalTime timeOut;
        private String qualification;
        private String martialStatus;
        private String status;
        private LocalDateTime dated;
        private LocalDateTime updated;
        private List<Long> gradeIds;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SubjectDTO {
        private Long id;
        private String name;
        private Long userId;
        private String code;
        private String publisher;
        private String edition;
        private String status;
        private Long gradeId;
        private LocalDateTime dated;
        private LocalDateTime updated;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VehicleDTO {
        private Long id;
        private String name;
        private String number;
        private String driverName;
        private String driverMobile;
        private String ownerName;
        private String ownerMobile;
        private Long userId;
        private String status;
        private Long schoolId;
        private LocalDateTime dated;
        private LocalDateTime updated;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DiscountDTO {
        private Long id;
        private Long userId;
        private String name;
        private String di;
        private Integer amount;
        private LocalDate startDate;
        private LocalDate endDate;
        private String description;
        private String referenceName;
        private String referenceMobile;
        private String status;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FeeCollectionDTO {
        private Long id;
        private Long userId;
        private String en;
        private String dt;
        private Integer d;
        private Integer dd;
        private Integer da;
        private Integer f;
        private Integer fp;
        private LocalDate pd;
        private Integer od;
        private String odd;
        private String p;
        private String rb;
        private String ri;
        private String cn;
        private Integer vf;
        private Integer db;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AttendanceDTO {
        private Long id;
        private Long userId;
        private String en;
        private String sn;
        private Long grid;
        private String gn;
        private LocalTime in;
        private LocalTime out;
        private String status;
        private LocalDateTime dt;
        private String rem;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AlertsDTO {
        private Long id;
        private Long userId;
        private String c;
        private Long ut;
        private String at;
        private String deliveryType;
        private String dc;
        private String dp;
        private String ah;
        private String am;
        private String alertSignature;
        private LocalDate sd;
        private LocalDate ed;
        private String st;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AlertChannelDTO {
        private Long id;
        private Long userId;
        private String c;
        private String cn;
        private String ut;
        private LocalDateTime dt;
        private String s;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DashboardStatsDTO {
        private long totalSchools;
        private long totalStudents;
        private long totalStaff;
        private long totalGuardians;
    }
}
