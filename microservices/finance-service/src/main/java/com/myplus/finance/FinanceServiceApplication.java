package com.myplus.finance;

import org.modelmapper.ModelMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * finance-service — the shared payment ledger (AR subledger now; AP + General Ledger later). Every module
 * (POS/business, education fees, pharma, ecommerce, agriculture) records receipts/disbursements here, so the
 * future General Ledger posts from one consistent source. See docs/finance-service-design.md.
 */
@SpringBootApplication
public class FinanceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinanceServiceApplication.class, args);
    }

    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }
}
