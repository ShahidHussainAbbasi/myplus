package com.myplus.agriculture.dto;

import lombok.Getter;
import lombok.Setter;

public class AgricultureIncomeDTO extends AgricultureBaseDTO {

    @Setter @Getter
    private String incomeName = null;
    @Setter @Getter
    private String incomeType = null;
}
