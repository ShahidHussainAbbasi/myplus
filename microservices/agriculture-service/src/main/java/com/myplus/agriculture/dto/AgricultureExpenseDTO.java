package com.myplus.agriculture.dto;

import lombok.Getter;
import lombok.Setter;

public class AgricultureExpenseDTO extends AgricultureBaseDTO {

    @Setter @Getter
    private String expenseName = null;
    @Setter @Getter
    private String expenseType = null;
}
