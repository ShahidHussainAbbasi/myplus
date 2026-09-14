package com.myplus.education.controller;

import com.myplus.common.audit.AuditRecord;
import com.myplus.education.entity.FeeCollection;
import com.myplus.education.entity.Student;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.util.AppUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * BLK-0b — a fee receipt says who took the money.
 *
 * <p>After BLK-0a the finance ledger write is reachable only through {@code SubledgerService}, and of its three
 * callers only the fee receipt left no audit event. These cases pin the three things that matter: the row is
 * shaped like business-service's receipt (so one trail filter shows both), a zero tender records nothing, and a
 * failed audit write never turns a recorded payment into an error the clerk would re-submit.
 *
 * <p>Pure reflection over the private helper, the same way {@link FeeGlMethodTest} does it: no Spring, no DB,
 * no Docker, so it runs on every {@code mvn test}.
 */
class FeeReceiptAuditTest {

    private EduAuditService audit;
    private AppUtil appUtil;
    private FeeCollectionController controller;

    @BeforeEach
    void setUp() throws Exception {
        audit = mock(EduAuditService.class);
        appUtil = mock(AppUtil.class);
        controller = new FeeCollectionController();
        inject("auditService", audit);
        inject("appUtil", appUtil);
    }

    private void inject(String field, Object value) throws Exception {
        Field f = FeeCollectionController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private void auditReceipt(FeeCollection fc, Student student, int tendered) throws Exception {
        Method m = FeeCollectionController.class.getDeclaredMethod(
                "auditReceipt", FeeCollection.class, Student.class, int.class);
        m.setAccessible(true);
        try {
            m.invoke(controller, fc, student, tendered);
        } catch (InvocationTargetException e) {
            // Unwrap, so a thrown exception fails the test as ITSELF rather than as a reflection wrapper.
            throw (Exception) e.getCause();
        }
    }

    private static FeeCollection fee(long id, String enrollNo, String receivedIn) {
        FeeCollection fc = new FeeCollection();
        fc.setId(id);
        fc.setEnrollNo(enrollNo);
        fc.setReceivedIn(receivedIn);
        return fc;
    }

    @Test @DisplayName("a fee receipt is audited as RECEIPT/STUDENT, keyed by the ledger's reference, at the tendered amount")
    void receiptIsAudited() throws Exception {
        Student st = new Student();
        st.setName("Ayesha Khan");

        auditReceipt(fee(4217L, "EN-88", "Check"), st, 5000);

        ArgumentCaptor<AuditRecord> rec = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(rec.capture());
        AuditRecord r = rec.getValue();

        // Same action + party-type shape as business-service's RECEIPT/CUSTOMER row.
        assertEquals("RECEIPT", r.getAction());
        assertEquals("STUDENT", r.getEntityType());
        // The fee collection id IS the `reference` the ledger stores — this is what joins the two rows.
        assertEquals("4217", r.getEntityRef());
        assertEquals(0, new BigDecimal("5000").compareTo(r.getAmount()), "the full tender, not the settled part");
        assertTrue(r.getDetails().contains("enrollNo=EN-88"), r.getDetails());
        assertTrue(r.getDetails().contains("student=Ayesha Khan"), r.getDetails());
        // Recorded in finance's vocabulary, so it reads the same as the ledger row it sits beside.
        assertTrue(r.getDetails().contains("method=CHEQUE"), r.getDetails());
    }

    @Test @DisplayName("nothing tendered → nothing audited (a charge-only row takes no money)")
    void zeroTenderIsNotAReceipt() throws Exception {
        auditReceipt(fee(1L, "EN-1", "Cash"), null, 0);
        verifyNoInteractions(audit);
    }

    @Test @DisplayName("a receipt with no student on record is still audited — the money was still handed over")
    void missingStudentStillAudited() throws Exception {
        auditReceipt(fee(9L, "EN-GONE", "Cash"), null, 300);

        ArgumentCaptor<AuditRecord> rec = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(rec.capture());
        assertEquals("9", rec.getValue().getEntityRef());
        assertTrue(rec.getValue().getDetails().contains("enrollNo=EN-GONE"));
    }

    @Test @DisplayName("⭐ a failed audit write is swallowed — it must never turn a recorded payment into an error")
    void auditFailureNeverFailsThePayment() throws Exception {
        RuntimeException boom = new RuntimeException("audit outbox unavailable");
        doThrow(boom).when(audit).record(any(AuditRecord.class));

        // Must not throw: an ERROR here would invite the clerk to submit the same money again.
        auditReceipt(fee(5L, "EN-5", "Cash"), null, 1200);

        // ...and must not vanish either: the failure is logged where every other failure here is.
        verify(appUtil).le(eq(FeeCollectionController.class), eq(boom));
    }
}
