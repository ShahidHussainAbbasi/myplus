package com.myplus.marketplace.multiseller.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.entity.MarketplaceCustomer;
import com.myplus.marketplace.multiseller.entity.MarketplaceCustomerSession;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.repository.MarketplaceCustomerRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceCustomerSessionRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1e2 — the marketplace customer: phone + password (owner's ruling: no SMS provider exists).
 *
 * <p><b>The phone is not proven.</b> So an order joins an account only by PROOF: placed while signed in, or claimed
 * with its number + phone (the proof tracking already asks for). An account never sees orders "by its phone" — anyone
 * could register someone else's number.
 *
 * <p>Sessions: a 256-bit random token handed to the monolith (which keeps it in an HttpOnly cookie); only its SHA-256
 * is stored. 30-day idle expiry, rotated at sign-in, revoked at sign-out. Five wrong passwords lock the phone for 15
 * minutes; an unknown phone and a wrong password read the same.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceCustomerService {

    static final int MAX_FAILED = 5;
    static final Duration LOCK = Duration.ofMinutes(15);
    static final Duration IDLE = Duration.ofDays(30);
    static final String WRONG = "The phone number or password is not right.";
    static final String SIGN_IN = "Sign in to continue.";
    /** A short refusal list; the length rule does most of the work. */
    private static final Set<String> COMMON = Set.of("password", "password1", "12345678", "123456789", "1234567890",
            "qwerty123", "11111111", "00000000", "iloveyou", "pakistan", "pakistan1", "abcd1234", "admin123");

    private final MarketplaceCustomerRepository customers;
    private final MarketplaceCustomerSessionRepository sessions;
    private final MarketplaceOrderRepository orders;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();

    /** What a signed-in call returns: the session token (for the cookie) and who it is. */
    public record Signed(String token, Long customerId, String name, String phone) { }

    @Transactional
    public Signed register(String phone, String name, String password, String email) {
        String d = phone(phone);
        String n = trim(name);
        if (n == null || n.length() < 2 || n.length() > 120) throw new ValidationException("Enter your name.");
        String e = trim(email);
        if (e != null && (e.length() > 254 || !e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")))
            throw new ValidationException("Enter a valid email, or leave it empty.");
        checkPassword(password, d);
        if (customers.findByPhone(d).isPresent())
            throw new ValidationException("This phone number already has an account. Sign in instead.");
        MarketplaceCustomer c = new MarketplaceCustomer();
        c.setPhone(d);
        c.setName(n);
        c.setEmail(e == null ? null : e.toLowerCase(Locale.ROOT));
        c.setPasswordHash(encoder.encode(password));
        c.setFailedLogins(0);
        try {
            c = customers.saveAndFlush(c);
        } catch (DataIntegrityViolationException race) {
            throw new ValidationException("This phone number already has an account. Sign in instead.");
        }
        return open(c);
    }

    /** {@code noRollbackFor}: a wrong password must COUNT, so its failed-login increment has to commit. */
    @Transactional(noRollbackFor = ValidationException.class)
    public Signed login(String phone, String password) {
        String d = phoneOrNull(phone);
        MarketplaceCustomer c = d == null ? null : customers.findByPhone(d).orElse(null);
        if (c == null || password == null) throw new ValidationException(WRONG);
        LocalDateTime now = LocalDateTime.now();
        if (c.getLockedUntil() != null && c.getLockedUntil().isAfter(now))
            throw new ValidationException("Too many wrong passwords. Please try again in 15 minutes.");
        if (!encoder.matches(password, c.getPasswordHash())) {
            int failed = (c.getFailedLogins() == null ? 0 : c.getFailedLogins()) + 1;
            c.setFailedLogins(failed >= MAX_FAILED ? 0 : failed);
            if (failed >= MAX_FAILED) c.setLockedUntil(now.plus(LOCK));
            customers.save(c);
            throw new ValidationException(WRONG);
        }
        c.setFailedLogins(0);
        c.setLockedUntil(null);
        customers.save(c);
        return open(c);
    }

    @Transactional
    public void logout(String token) {
        sessions.findByTokenHash(hash(token)).ifPresent(s -> {
            if (s.getRevokedAt() == null) {
                s.setRevokedAt(LocalDateTime.now());
                sessions.save(s);
            }
        });
    }

    /** The customer behind a session token, or "Sign in to continue." Slides the idle expiry. */
    @Transactional
    public MarketplaceCustomer authenticate(String token) {
        if (token == null || token.isBlank()) throw new ValidationException(SIGN_IN);
        LocalDateTime now = LocalDateTime.now();
        MarketplaceCustomerSession s = sessions.findByTokenHash(hash(token)).orElse(null);
        if (s == null || s.getRevokedAt() != null || !s.getExpiresAt().isAfter(now)) throw new ValidationException(SIGN_IN);
        if (Duration.between(s.getLastSeenAt(), now).toMinutes() >= 5) {   // touch at most every 5 minutes
            s.setLastSeenAt(now);
            s.setExpiresAt(now.plus(IDLE));
            sessions.save(s);
        }
        return customers.findById(s.getCustomerId()).orElseThrow(() -> new ValidationException(SIGN_IN));
    }

    /** The same session, or none: a public read that is better with a customer but works without one. */
    public Long customerIdOrNull(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            return authenticate(token).getId();
        } catch (ValidationException notSignedIn) {
            return null;
        }
    }

    /**
     * Attach an order placed before signing in, with the SAME proof tracking asks for: its number and the phone it was
     * placed with. Never moves an order another account already owns.
     */
    @Transactional
    public MarketplaceOrder claim(MarketplaceCustomer c, String orderNo, String phone) {
        MarketplaceOrder o = orderNo == null ? null
                : orders.findByOrderNo(orderNo.trim().toUpperCase(Locale.ROOT)).orElse(null);
        if (o == null || phone == null || !MarketplaceCheckoutService.digits(o.getCustomerPhone())
                .equals(MarketplaceCheckoutService.digits(phone)))
            throw new ResourceNotFoundException("No such order. Check the order number and the phone it was placed with.");
        if (o.getCustomerId() != null && !o.getCustomerId().equals(c.getId()))
            throw new ValidationException("This order is already in another account.");
        if (o.getCustomerId() == null) {
            o.setCustomerId(c.getId());
            orders.save(o);
        }
        return o;
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────────────────

    private Signed open(MarketplaceCustomer c) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        MarketplaceCustomerSession s = new MarketplaceCustomerSession();
        LocalDateTime now = LocalDateTime.now();
        s.setCustomerId(c.getId());
        s.setTokenHash(hash(token));
        s.setCreatedAt(now);
        s.setLastSeenAt(now);
        s.setExpiresAt(now.plus(IDLE));
        sessions.save(s);
        return new Signed(token, c.getId(), c.getName(), c.getPhone());
    }

    static String hash(String token) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void checkPassword(String password, String phoneDigits) {
        if (password == null || password.length() < 8 || password.length() > 128)
            throw new ValidationException("Choose a password of at least 8 characters.");
        String p = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(p) || MarketplaceCheckoutService.digits(password).equals(phoneDigits) && phoneDigits.length() >= 7)
            throw new ValidationException("That password is too easy to guess. Choose another.");
    }

    private static String phone(String phone) {
        String d = phoneOrNull(phone);
        if (d == null) throw new ValidationException("Enter your phone number.");
        return d;
    }

    private static String phoneOrNull(String phone) {
        String t = trim(phone);
        if (t == null || t.length() > 32 || !t.matches("[0-9+()\\-\\s]+")) return null;
        String d = MarketplaceCheckoutService.digits(t);
        return d.length() < 7 || d.length() > 15 ? null : d;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
