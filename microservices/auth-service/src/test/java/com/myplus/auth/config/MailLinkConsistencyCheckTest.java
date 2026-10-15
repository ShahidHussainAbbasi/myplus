package com.myplus.auth.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * DEV-ENV-1 — the links inside e-mails and the mail server must belong to the same environment.
 * The first case is the defect seen on 2026-10-12: local mail, production links.
 */
class MailLinkConsistencyCheckTest {

    private static final String PROD_LINK = "https://maxtheservice.com/user/changePassword";
    private static final String DEV_LINK = "http://localhost:8080/user/changePassword";

    @Test
    void localMailWithProductionLinksIsAMismatch() {
        assertTrue(MailLinkConsistencyCheck.problem("mailpit", PROD_LINK).isPresent());
        assertTrue(MailLinkConsistencyCheck.problem("localhost", PROD_LINK).isPresent());
    }

    @Test
    void realMailWithLocalhostLinksIsAMismatch() {
        assertTrue(MailLinkConsistencyCheck.problem("smtp.gmail.com", DEV_LINK).isPresent());
        assertTrue(MailLinkConsistencyCheck.problem("smtp.gmail.com", "http://127.0.0.1:8080/x").isPresent());
    }

    @Test
    void eachEnvironmentOnItsOwnIsFine() {
        assertFalse(MailLinkConsistencyCheck.problem("mailpit", DEV_LINK).isPresent(), "dev");
        assertFalse(MailLinkConsistencyCheck.problem("smtp.gmail.com", PROD_LINK).isPresent(), "production");
        assertFalse(MailLinkConsistencyCheck.problem(" MAILPIT ", DEV_LINK).isPresent(), "case and spaces");
    }

    @Test
    void nothingToCompareIsNotAnError() {
        assertFalse(MailLinkConsistencyCheck.problem(null, PROD_LINK).isPresent());
        assertFalse(MailLinkConsistencyCheck.problem("mailpit", "").isPresent());
        assertFalse(MailLinkConsistencyCheck.problem("mailpit", "not a url ::").isPresent());
    }
}
