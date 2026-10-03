package com.myplus.market;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * market-service — the MaxTheService platform marketplace (MP-0 onwards).
 *
 * <p>Not {@code marketplace-service}: that one is a single shop's own web storefront, where the shop owns the
 * customer and every row is that shop's. Here the operator owns the customer relationship and the rows, and
 * each seller (an ordinary tenant that opted in) is named on the rows it is accountable for. Ruling R-1 in
 * microservices/docs/platform-marketplace-design.md.
 *
 * <p>{@code @EnableScheduling} drives the audit outbox relay (and, from MP-5, the acceptance-deadline worker).
 */
@SpringBootApplication
@EnableScheduling
public class MarketServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarketServiceApplication.class, args);
    }
}
