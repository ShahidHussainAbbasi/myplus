package com.myplus.finance.service;

import com.myplus.common.security.CurrentUser;
import com.myplus.finance.dto.AllocationDTO;
import com.myplus.finance.dto.PaymentDTO;
import com.myplus.finance.dto.RecordPaymentRequest;
import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.Payment;
import com.myplus.finance.entity.PaymentAllocation;
import com.myplus.finance.entity.PaymentDirection;
import com.myplus.finance.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The payment ledger service. Records receipts/disbursements (with allocations) tenant-scoped and GL-ready, and
 * answers party history/totals. It does NOT allocate money itself — the owning module (which knows its invoices)
 * passes the allocations; finance-service faithfully records them so every module shares one ledger.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;

    @Transactional
    public PaymentDTO record(RecordPaymentRequest req) {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();

        PaymentDirection direction = req.getDirection() != null ? req.getDirection() : PaymentDirection.RECEIPT;
        Payment p = Payment.builder()
                .direction(direction)
                .partyType(req.getPartyType())
                .partyId(req.getPartyId())
                .partyName(req.getPartyName())
                .amount(req.getAmount())
                .method(req.getMethod())
                .paidOn(req.getPaidOn() != null ? req.getPaidOn() : LocalDate.now())
                .reference(req.getReference())
                .sourceModule(req.getSourceModule())
                .note(req.getNote())
                .organizationId(orgId)
                .userId(userId)
                .createdAt(LocalDateTime.now())
                .receiptNo(nextReceiptNo(direction, orgId, userId))
                .allocations(new ArrayList<>())
                .build();

        if (req.getAllocations() != null) {
            for (AllocationDTO a : req.getAllocations()) {
                if (a == null || a.getAmount() == null || a.getAmount().signum() <= 0) continue;
                p.addAllocation(PaymentAllocation.builder()
                        .docType(a.getDocType() != null ? a.getDocType() : "INVOICE")
                        .docId(a.getDocId()).docNo(a.getDocNo()).amount(a.getAmount())
                        .build());
            }
        }
        return toDTO(paymentRepository.save(p));
    }

    @Transactional(readOnly = true)
    public List<PaymentDTO> listByParty(PartyType partyType, Long partyId) {
        List<PaymentDTO> out = new ArrayList<>();
        for (Payment p : paymentRepository.findByPartyScoped(partyType, partyId,
                CurrentUser.organizationId(), CurrentUser.userId())) {
            out.add(toDTO(p));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public BigDecimal totalByParty(PartyType partyType, Long partyId) {
        BigDecimal sum = paymentRepository.sumByPartyScoped(partyType, partyId,
                CurrentUser.organizationId(), CurrentUser.userId());
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /** Per-org, per-direction voucher sequence: RECEIPT → RCPT-######, DISBURSEMENT (AP) → PV-###### (payment
     *  voucher). Numbering is direction-scoped so receipts and payments don't share one running number. */
    private String nextReceiptNo(PaymentDirection direction, Long orgId, Long userId) {
        long n = paymentRepository.countByDirectionScoped(direction, orgId, userId) + 1;
        String prefix = direction == PaymentDirection.DISBURSEMENT ? "PV" : "RCPT";
        return String.format("%s-%06d", prefix, n);
    }

    private PaymentDTO toDTO(Payment p) {
        List<AllocationDTO> allocs = new ArrayList<>();
        for (PaymentAllocation a : p.getAllocations()) {
            allocs.add(AllocationDTO.builder()
                    .docType(a.getDocType()).docId(a.getDocId()).docNo(a.getDocNo()).amount(a.getAmount())
                    .build());
        }
        return PaymentDTO.builder()
                .id(p.getId()).direction(p.getDirection())
                .partyType(p.getPartyType()).partyId(p.getPartyId()).partyName(p.getPartyName())
                .amount(p.getAmount()).method(p.getMethod()).paidOn(p.getPaidOn())
                .reference(p.getReference()).sourceModule(p.getSourceModule())
                .receiptNo(p.getReceiptNo()).note(p.getNote()).createdAt(p.getCreatedAt())
                .allocations(allocs)
                .build();
    }
}
