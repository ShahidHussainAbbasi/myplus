package com.myplus.agriculture.entity;

import java.io.Serializable;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "agriculture_income")
public class AgricultureIncome implements Serializable {
    private static final long serialVersionUID = 1L;

    public AgricultureIncome() {
    }

    public AgricultureIncome(Long userId) {
        this.userId = userId;
    }

    public AgricultureIncome(Long userId, Long landId, String incomeName, LocalDate updated) {
        this.userId = userId;
        this.landId = landId;
        this.incomeName = incomeName;
        this.updated = updated;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "agri_income_id", unique = true, nullable = false)
    @Setter @Getter
    private Long id;

    @Column(name = "user_id", nullable = false)
    @Setter @Getter
    private Long userId;

    @Column(name = "organization_id")
    @Setter @Getter
    private Long organizationId;       // tenant scope (from gateway X-Org-Id); user_id kept as audit

    @Column(name = "user_type")
    @Setter @Getter
    private String userType;

    @Column(name = "land_id")
    @Setter @Getter
    private Long landId = null;

    @Column(name = "land_name")
    @Setter @Getter
    private String landName;

    @Column(name = "crop_name")
    @Setter @Getter
    private String cropName;

    @Column(name = "crop_type")
    @Setter @Getter
    private String cropType;

    @Column(name = "income_type")
    @Setter @Getter
    private String incomeType;

    @Column(name = "income_name")
    @Setter @Getter
    private String incomeName;

    @Column(name = "amount")
    @Setter @Getter
    private Float amount;

    @Column(name = "description")
    @Setter @Getter
    private String description;

    @Column(name = "dated", updatable = false)
    @Setter @Getter
    private LocalDate dated;

    @Column(name = "updated")
    @Setter @Getter
    private LocalDate updated;
}
