package com.myplus.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/**
 * EX-1 — STANDARDS D2: the migrations run on an EMPTY MySQL and the service boots under ddl-auto=validate.
 *
 * <p>This is the test that would have caught the two crash loops this project paid for (@Lob+TEXT, ENUM for a
 * String): Hibernate compares every entity column with V1 here, not on the first deploy. It also round-trips a
 * voucher with lines through the real schema and takes two numbers from the shared counter.
 *
 * <p>Skips without Docker — read the Skipped count (D2a).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ExpenseFlywayMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("spring.cloud.discovery.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
        r.add("expense.outbox.relay-delay-ms", () -> "3600000");
        r.add("audit.outbox.relay-delay-ms", () -> "3600000");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExpenseVoucherRepo vouchers;
    @Autowired private DocumentNumberService numbers;
    @Autowired private TransactionTemplate tx;

    @Test
    void v1_applies_and_the_entities_validate_against_it() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version = '1'", Integer.class);
        assertThat(applied).isEqualTo(1);
        for (String t : new String[] {"expense_category", "expense_voucher", "expense_voucher_line", "expense_outbox",
                "org_document_seq", "audit_outbox"}) {
            String engine = jdbc.queryForObject("SELECT ENGINE FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", String.class, t);
            assertThat(engine).as(t + " is InnoDB (row locks, FKs)").isEqualTo("InnoDB");
        }
    }

    @Test
    void a_voucher_with_lines_round_trips_and_numbers_are_per_org() {
        Long id = tx.execute(s -> {
            ExpenseVoucher v = new ExpenseVoucher();
            v.setOrganizationId(900L);
            v.setVoucherDate(LocalDate.now());
            v.setPaidFrom("CASH");
            v.setCreatedAt(LocalDateTime.now());
            ExpenseVoucherLine l = new ExpenseVoucherLine();
            l.setCategoryId(1L);
            l.setAccountCode("6000");
            l.setAmount(new BigDecimal("2500.00"));
            v.addLine(l);
            v.post(String.format("EXP-%06d", numbers.next(900L, "EXPENSE")), LocalDateTime.now());
            return vouchers.saveAndFlush(v).getId();
        });
        ExpenseVoucher back = tx.execute(s -> {
            ExpenseVoucher v = vouchers.findByIdAndOrganizationId(id, 900L).orElseThrow();
            v.getLines().size();
            return v;
        });
        assertThat(back.getVoucherNo()).isEqualTo("EXP-000001");
        assertThat(back.getTotal()).isEqualByComparingTo("2500.00");
        assertThat(back.getLines()).hasSize(1);
        assertThat(vouchers.findByIdAndOrganizationId(id, 901L)).as("another tenant cannot load it").isEmpty();

        Long second = tx.execute(s -> numbers.next(900L, "EXPENSE"));
        Long otherOrg = tx.execute(s -> numbers.next(901L, "EXPENSE"));
        assertThat(second).isEqualTo(2L);
        assertThat(otherOrg).as("each business has its own series").isEqualTo(1L);

        assertThat(vouchers.stampPosting(id, ExpenseVoucher.PS_POSTED_GL, null)).isEqualTo(1);
        assertThat(vouchers.findById(id).orElseThrow().getPostingStatus()).isEqualTo("POSTED_GL");
    }
}
