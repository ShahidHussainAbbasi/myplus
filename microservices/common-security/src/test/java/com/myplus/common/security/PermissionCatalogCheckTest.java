package com.myplus.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** RW-0c — a code a service names but the catalogue lacks is reported; codes it has are not. */
class PermissionCatalogCheckTest {

    @Test
    void reportsOnlyTheCodesTheCatalogueLacks_sorted() {
        assertEquals(List.of("clinic.gone", "rx.renamed"),
                PermissionCatalogCheck.missing(List.of("rx.renamed", "clinic.consult", "clinic.gone"),
                        List.of("clinic.consult", "rx.dispense")));
    }

    @Test
    void nothingMissingWhenEveryCodeExists() {
        assertEquals(List.of(), PermissionCatalogCheck.missing(List.of("rx.dispense"), List.of("rx.dispense", "x")));
    }

    @Test
    void anAbsentCatalogueReportsEveryCode() {
        assertEquals(List.of("a.b"), PermissionCatalogCheck.missing(List.of("a.b"), null));
    }
}
