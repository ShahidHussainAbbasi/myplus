package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.commerce.contracts.client.ExpenseTagClient;
import com.myplus.commerce.contracts.dto.ExpenseTagView;
import com.myplus.common.web.exception.ValidationException;

/** EX-2b — a tag is stored only when the owning module confirms it; the label is the module's. */
class ExpenseTagServiceTest {

    private final AtomicInteger educationCalls = new AtomicInteger();

    private ExpenseTagService svc() {
        ExpenseTagClient education = () -> {
            educationCalls.incrementAndGet();
            return List.of(new ExpenseTagView("SCHOOL", 1L, "Main Campus"), new ExpenseTagView("VEHICLE", 7L, "Bus (LEA-123)"));
        };
        ExpenseTagClient agriculture = () -> List.of(new ExpenseTagView("LAND", 9L, "North field"));
        ExpenseTagClient business = () -> List.of(new ExpenseTagView("SUPPLIER", 42L, "K-Electric"),
                new ExpenseTagView("STORE", 3L, "not a supplier"));
        return new ExpenseTagService(education, agriculture, business);
    }

    @Test @DisplayName("a confirmed tag returns the MODULE's label")
    void confirmed() {
        assertThat(svc().confirm("vehicle", 7L)).isEqualTo("Bus (LEA-123)");
        assertThat(svc().confirm("LAND", 9L)).isEqualTo("North field");
    }

    @Test @DisplayName("no tag at all is an untagged line, not an error")
    void untagged() {
        assertThat(svc().confirm(null, null)).isNull();
        assertThat(svc().confirm(" ", null)).isNull();
    }

    @Test @DisplayName("⭐ an id the module did not offer is refused — a foreign or out-of-branch record is never stored")
    void foreign_id_refused() {
        assertThatThrownBy(() -> svc().confirm("VEHICLE", 999L)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("not one of yours");
        // the land id exists, but under LAND — asked as VEHICLE it is someone else's type
        assertThatThrownBy(() -> svc().confirm("VEHICLE", 9L)).isInstanceOf(ValidationException.class);
    }

    @Test @DisplayName("an unknown type, or a type with no id, is refused by name")
    void bad_requests() {
        assertThatThrownBy(() -> svc().confirm("BOGUS", 1L)).hasMessageContaining("cannot be tagged");
        assertThatThrownBy(() -> svc().confirm("LAND", null)).hasMessageContaining("Choose");
    }

    @Test @DisplayName("a 3-line voucher reads each module's list ONCE")
    void one_call_per_module() {
        ExpenseTagService s = svc();
        var seen = new HashMap<String, List<ExpenseTagView>>();
        s.confirm("SCHOOL", 1L, seen);
        s.confirm("VEHICLE", 7L, seen);
        s.confirm("SCHOOL", 1L, seen);
        assertThat(educationCalls.get()).isEqualTo(1);
    }

    @Test @DisplayName("options for an unknown source are empty (the shop has no tags)")
    void unknown_source() {
        assertThat(svc().options("business")).isEmpty();
        assertThat(svc().options(null)).isEmpty();
    }

    @Test @DisplayName("FP-3: a bill's supplier is confirmed by business; its label is business's")
    void supplierConfirmed() {
        assertThat(svc().confirmSupplier(42L)).isEqualTo("K-Electric");
        assertThat(svc().suppliers()).extracting(ExpenseTagView::getType).containsOnly("SUPPLIER");
    }

    @Test @DisplayName("FP-3: a foreign or missing supplier is refused, and a supplier is never a LINE tag")
    void supplierRefused() {
        assertThatThrownBy(() -> svc().confirmSupplier(999L)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("not one of yours");
        assertThatThrownBy(() -> svc().confirmSupplier(null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> svc().confirm("SUPPLIER", 42L)).isInstanceOf(ValidationException.class);
        assertThat(svc().options("business")).isEmpty();
    }
}
