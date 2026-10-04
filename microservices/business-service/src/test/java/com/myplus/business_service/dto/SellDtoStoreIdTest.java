package com.myplus.business_service.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MM-2 — getAllSell now returns SellDTO, which therefore carries the sale's store. A sale's store is decided by the
 * server's location scope, so the field must travel OUT and never IN.
 */
class SellDtoStoreIdTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("a request cannot set storeId — it is read-only on the wire")
    void requestCannotSetStore() throws Exception {
        SellDTO in = json.readValue("{\"productId\":7,\"storeId\":99}", SellDTO.class);
        assertThat(in.getProductId()).isEqualTo(7L);
        assertThat(in.getStoreId()).as("ignored on input").isNull();
    }

    @Test
    @DisplayName("a response carries storeId")
    void responseCarriesStore() throws Exception {
        SellDTO out = new SellDTO();
        out.setStoreId(3L);
        assertThat(json.writeValueAsString(out)).contains("\"storeId\":3");
    }
}
