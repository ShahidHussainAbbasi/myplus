package com.myplus.pharma.service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.pharma.dto.PrescriptionDTO;
import com.myplus.pharma.dto.PrescriptionItemDTO;

import lombok.RequiredArgsConstructor;

/**
 * HMS H1 (client ruling L-1) — a doctor's prescription may name only medicines that are in THIS pharmacy's catalogue.
 * The doctor's screen offers only that list, but the server took any product id; a line naming a product the pharmacy
 * does not have would reach the pharmacist and fail at Dispense. Refused at source, naming the line, as e-prescribing
 * services refuse an uncoded item.
 *
 * <p>One batch call, read LIVE ({@code fresh=true}: a safety decision is never taken from a cached row — CACHE-3), and
 * scoped to the caller's business by the forwarded headers, so another business's product is simply absent. If the
 * catalogue cannot be asked, the Submit is refused in words and is safe to repeat (the clinic's Submit is idempotent).
 */
@Component
@RequiredArgsConstructor
public class PrescribedProductCheck {

    private final CatalogClient catalog;

    public void assertInCatalogue(PrescriptionDTO dto) {
        if (dto == null || dto.getItems() == null || dto.getItems().isEmpty()) return;
        List<Long> ids = dto.getItems().stream().map(PrescriptionItemDTO::getProductId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;   // create() names the line without a product
        List<ProductRef> found;
        try {
            found = catalog.getProductsFresh(ids, true);
        } catch (RuntimeException unreachable) {
            throw new ValidationException("The pharmacy's medicine list could not be checked. Press Submit again.");
        }
        Set<Long> known = (found == null ? List.<ProductRef>of() : found).stream()
                .map(ProductRef::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        for (PrescriptionItemDTO it : dto.getItems()) {
            if (it.getProductId() != null && !known.contains(it.getProductId())) {
                String which = it.getMedicineName() == null || it.getMedicineName().isBlank()
                        ? "A medicine" : "'" + it.getMedicineName().trim() + "'";
                throw new ValidationException(which + " is not in this pharmacy's list. Choose it again from the list.");
            }
        }
    }
}
