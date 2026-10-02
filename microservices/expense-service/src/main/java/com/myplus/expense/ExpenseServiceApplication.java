package com.myplus.expense;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * expense-service — Expense Management (EX-1). Records what a business spends and posts it to the books
 * through finance-service, which stays the only writer of journals. Business-independent: every module plugs
 * in through the same API. Design: microservices/docs/expense-management-design.md.
 *
 * <p>{@code @EnableScheduling} drives the outbox retry relay (a posting that missed its after-commit attempt).
 */
@SpringBootApplication
@EnableScheduling
public class ExpenseServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExpenseServiceApplication.class, args);
    }
}
