package com.myplus.commerce.contracts.client;

import java.util.List;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.myplus.commerce.contracts.dto.ExpenseTagView;

/**
 * EX-2b — the expense-tag SPI every module that owns taggable things implements: {@code GET /expense-tags} returns
 * what the CALLER may see (the module applies its own scoping, branch grants included). expense-service holds one
 * proxy per module, chosen by tag type — the module owns WHO, expense-service owns only the reference.
 */
@HttpExchange(accept = "application/json")
public interface ExpenseTagClient {

    @GetExchange("/expense-tags")
    List<ExpenseTagView> tags();
}
