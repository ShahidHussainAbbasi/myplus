package com.myplus.auth.config;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * DEV-ENV-1 (2026-10-12) — the links inside e-mails and the mail server must belong to the SAME environment.
 *
 * <p>The defect it guards: on a dev PC, auth-service was redeployed by hand without the dev settings. Mail still went
 * to the local catcher (Mailpit), but every set-password link pointed at https://maxtheservice.com — a tester who
 * opened one landed on PRODUCTION with a token production has never seen. Nothing failed and nothing was logged.
 * The opposite mix (real mail, localhost links) would e-mail real people links that open nothing.
 *
 * <p>So at start-up the two are compared and a mismatch is logged as an ERROR, naming both values. It never stops the
 * service: a mail setting must not take sign-in down. The fix is always configuration — on a dev PC
 * {@code COMPOSE_FILE=docker-compose.yml;docker-compose.dev.yml} in .env; on production no such line.
 */
@Component
public class MailLinkConsistencyCheck {

    private static final Logger log = LoggerFactory.getLogger(MailLinkConsistencyCheck.class);

    /** Mail servers that only exist on a developer's machine: the catcher container, or this host. */
    private static final Set<String> LOCAL_MAIL = Set.of("mailpit", "localhost", "127.0.0.1");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.reset-password-url:}")
    private String resetPasswordUrl;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        problem(mailHost, resetPasswordUrl).ifPresentOrElse(
                p -> log.error("DEV-ENV-1 e-mail links and mail server disagree: {}", p),
                () -> log.info("DEV-ENV-1 e-mail links {} — mail server {}", resetPasswordUrl, mailHost));
    }

    /** The mismatch, in words, or empty when the two agree (or either is not set — nothing to compare). */
    static Optional<String> problem(String mailHost, String linkUrl) {
        if (isBlank(mailHost) || isBlank(linkUrl)) return Optional.empty();
        boolean localMail = LOCAL_MAIL.contains(mailHost.trim().toLowerCase(Locale.ROOT));
        String linkHost = hostOf(linkUrl);
        if (linkHost == null) return Optional.empty();
        boolean localLink = LOCAL_HOSTS.contains(linkHost);
        if (localMail && !localLink) {
            return Optional.of("mail is caught locally (" + mailHost + ") but the links open " + linkUrl
                    + " — a tester would land on that site with a token it does not know. On a dev PC set "
                    + "COMPOSE_FILE=docker-compose.yml;docker-compose.dev.yml in .env and redeploy auth-service.");
        }
        if (!localMail && localLink) {
            return Optional.of("mail goes to a real server (" + mailHost + ") but the links open " + linkUrl
                    + " — real people would get links that open nothing. Production's .env must not name "
                    + "docker-compose.dev.yml, and RESET_PASSWORD_URL must be the public address.");
        }
        return Optional.empty();
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url.trim()).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
