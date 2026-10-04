# Slice MKT-1e2 — the marketplace customer: account, online payment, My orders, cancel

**Status:** BUILT and verified live (2026-10-03): gate 9/9, walk M-1e2-01..06 6/6, forged identity refused 5/5 —
see `../marketplace/live-verification-2026-10-03.md` §6.

**Rulings taken for this slice (2026-10-03, by the owner):**
- **Sign-in is phone + password.** No SMS provider exists (notification-service defines `Channel.SMS` but deliberately
  does not dispatch it), so a phone number is never *proven* by a code.
- **Online payment uses the existing sandbox `PaymentGateway` now**; the real provider is plugged in by configuration
  when its keys exist. Cash on delivery stays the default.

Source: R-MKT-5 (a platform-scoped customer, party-bridged; the per-store `storefront_customer` is untouched), source
§10, §17, §19, §22. Builds on MKT-1e (one-seller COD checkout, hold, acceptance window).

## 1. Document

A shopper can create a MaxTheService account with their **phone number and a password**, sign in, and see **My orders**:
every marketplace order that is provably theirs, with its seller, status and terms. While the seller has not yet
answered, the shopper can **cancel** the order: the stock is released, and a paid order is refunded. At checkout a
signed-in shopper can choose **Pay online now** (card, through the payment gateway) instead of cash on delivery; the
money is collected by MaxTheService and owed to the seller by the settlement ledger (MKT-1g). Nothing is promised that
is not true: a declined card places nothing; a payment whose answer is lost is never charged twice.

### 1a. Trace (RULE 0)

| What | Found | Consequence |
|---|---|---|
| Who may see an order today | anyone holding the **order number + the phone** it was placed with (`MarketplaceCheckoutService.track`) | the account must not be weaker than that |
| Phone ownership | **never verified** — no SMS provider | an account cannot see orders "by its phone": anyone could register someone else's number and read their name and address. Orders attach to an account **only by proof**: placed while signed in, or **claimed** with order number + phone (exactly the tracking proof) |
| `mkt_order` readers (5 queries) | idempotency replay · tracking by number · operator list ×2 · the 3-waiting-per-phone guard | a nullable `customer_id` changes none of them: all 5 still want every order. One new query: `findByCustomerIdOrderByCreatedAtDesc` (My orders) |
| `mkt_order` writers | checkout (creates) · seller accept / reject · sweeper expire / orphan cancel (status only) | none may touch `customer_id`. New writers: checkout signed in (sets it), claim (sets it once, never moves it), customer cancel, refund (payment status) |
| Existing per-store accounts | `CustomerAccountService`: email + BCrypt + an opaque token **stored in clear on the customer row**, no expiry visible | not reused (R-MKT-5) and not copied: sessions get their own table, the token is stored **hashed**, with expiry and revocation |
| Public wire | monolith `MarketplacePublicController` → gateway `/api/marketplace/public/**` (open: no JWT, **identity headers not stripped on open paths**) → marketplace-service | the customer session cannot ride `X-User-*` (they are gateway identity, trusted only with `X-Internal-Secret`). It is its own header `X-Mkt-Session`, set by the monolith from an **HttpOnly** cookie the browser's script never sees, resolved by marketplace-service itself. Proven live 2026-10-03: services drop `X-User-*` without the secret (`INTERNAL_SECRET` is set in compose and `.env.local`) |
| CSRF | `CookieCsrfTokenRepository.withHttpOnlyFalse()`; anonymous checkout POST already sends the token; a lapsed token redirects to /login (read as "page expired" since the walk fix) | every account POST sends the token the same way |
| `PaymentGateway` | `charge(token, amount)` / `refund(chargeId, amount)`; sandbox: token `fail` declines; one caller (`OrderService:821`, single-store) | add `charge(token, amount, idempotencyKey)` as a **default method** delegating to the old one (sandbox, existing caller unchanged); a real provider implements it with the PSP's idempotency key |
| Charge vs hold order | MKT-1e: order row → stock hold → SUBMITTED | charge **after** the hold succeeds (never charge for stock that cannot be held); a decline releases the hold and cancels with "Your card was declined." |
| Money owed | platform-collected payment = MaxTheService holds the seller's money | every charge and refund is a fact in `mkt_payment` that MKT-1g's ledger consumes; **nothing is paid out before 1g** |
| Abuse guard | 3 waiting orders per phone (digits) | unchanged; plus login throttling (below) |
| Password reset | no SMS, email optional | reset by email if the account has one; otherwise MaxTheService support verifies an order number + phone and issues a one-time reset code (15 min) |

### 1b. Standards

| Dimension | Rule |
|---|---|
| Domain | An order belongs to an account only by proof (placed signed in, or claimed with number + phone). A claim never moves an order already owned by another account ("This order is already in another account."). Cancel only while the seller has not answered (`OFFERED`); after that it is a support case (MKT-1f). A refund is owed exactly once per charge |
| Security | BCrypt (cost 12) · password 8–128 chars, not the phone, not in a short common-password list · session token 256-bit random, stored as SHA-256, cookie `HttpOnly; Secure; SameSite=Lax; Path=/marketplace`, 30-day idle expiry, rotated at sign-in, revoked at sign-out and at password change · 5 wrong passwords per phone → 15-minute lock, the same sentence for unknown phone and wrong password ("The phone number or password is not right.") · CSRF on every POST · phone normalised to digits, one account per phone |
| SaaS / tenancy | the customer is platform-scoped (no org); sellers never see the account, only the order's delivery details as today; the operator sees accounts only through orders |
| Live-modules rule | new tables + one nullable column; existing reads and writes unchanged; COD path unchanged |
| Microservices | marketplace-service owns customer, session, payment facts; the monolith only relays and holds the cookie; no new service |
| Patterns | saga with compensation extended (hold → charge; decline/cancel/reject/expire → release + refund) · idempotency key on the charge (the order's key) · outbox-free here (synchronous, recorded facts) · optimistic lock on cancel |
| Testing | unit: claim needs the right phone; an owned order cannot be claimed; cancel only while OFFERED; decline releases the hold; refund once on reject / expire / cancel; lost charge answer + retry = one charge; lockout after 5; token stored hashed. Testcontainers: V29 on MySQL. Gate `mkt-1e2-account-payment.cy.js`. Recorded walk cases M-1e2-01… |

## 2. Design

### 2.1 Data (V29, VARCHAR statuses, `ddl-auto=validate`)

| Table | Columns |
|---|---|
| `mkt_customer` | `id` PK · `phone` varchar(32) UNIQUE (digits) · `name` varchar(120) · `email` varchar(254) NULL · `password_hash` varchar(100) · `failed_logins` int · `locked_until` datetime NULL · `created_at` · `updated_at` · `version` |
| `mkt_customer_session` | `id` PK · `customer_id` FK · `token_hash` char(64) UNIQUE · `created_at` · `last_seen_at` · `expires_at` · `revoked_at` NULL |
| `mkt_payment` | `id` PK · `mkt_order_id` FK · `kind` varchar(16) (`CHARGE` / `REFUND`) · `status` varchar(16) (`PENDING` / `SUCCEEDED` / `FAILED`) · `amount` decimal(19,2) · `provider` varchar(32) · `provider_ref` varchar(80) NULL · `idempotency_key` varchar(100) UNIQUE · `reason` varchar(300) NULL · `created_at` · `updated_at` |
| `mkt_order` | + `customer_id` BIGINT NULL, index `idx_mkt_order_customer_created (customer_id, created_at)` |

`payment_mode` gains `CARD`; `payment_status` follows the domain's existing `MarketplaceStateMachines.PAYMENT`: `UNPAID → CAPTURED` (an immediate charge is a capture) `→ REFUNDED`, or `UNPAID → FAILED` (VARCHAR, no enum).

### 2.2 Flows

```
Register   phone+name+password(+email) → BCrypt → session → Set-Cookie
Sign in    phone+password → lock check → BCrypt → session (rotated)          wrong ×5 → locked 15 min
Claim      order no + phone (the tracking proof) → customer_id set once      owned elsewhere → refused
Checkout   (signed in) customer_id set · COD as today
           CARD: order → hold → mkt_payment CHARGE PENDING (key = order key) → gateway.charge(key)
                 ok → CAPTURED, SUBMITTED · declined → release hold, CANCELLED "Your card was declined."
                 no answer → stays PENDING; the sweeper asks again with the SAME key (never a second charge)
Cancel     owner, seller order OFFERED → CANCELLED (customer), release hold, refund if CAPTURED
Refund     on reject · expire · cancel: mkt_payment REFUND (key = "refund:" + charge id) → REFUNDED, once
```

### 2.3 Endpoints (public, through the monolith; session from the cookie)

`POST /marketplace/account/register` · `POST /marketplace/account/login` · `POST /marketplace/account/logout` ·
`GET /marketplace/account/me` · `GET /marketplace/account/orders` · `POST /marketplace/account/claim` ·
`POST /marketplace/account/orders/{no}/cancel` · checkout gains `paymentMode` (`COD` default, `CARD` + `cardToken`
when signed in).

### 2.4 Screens

The public page gains a sign-in link in its top bar, an account panel (sign in / create account / forgot password),
**My orders** (each order: number, seller, status, total, terms; Cancel while waiting), "Add an order you placed
before" (number + phone), and at checkout a payment choice (Cash on delivery · Pay online now) for a signed-in
shopper. Same theme, same languages (six bundles), same keyboard and phone rules as MKT-1d.

## 3. Plan

1. V29 + entities + repositories (FlywayMigrationTest).
2. `MarketplaceCustomerService` (register, login, lock, sessions, claim) + unit tests.
3. `PaymentGateway` default overload; `MarketplacePaymentService` (charge, refund-once, reconcile PENDING) + tests.
4. Checkout (customer link, CARD) · cancel · refunds on reject / expire / cancel — every existing writer re-read.
5. Monolith relay + HttpOnly cookie + CSRF; page screens + i18n (six bundles, parity checked).
6. Gate `mkt-1e2-account-payment.cy.js`; recorded walk cases; RTM; manual page republished.
7. Live run on the stack; fix what it finds; commit; push.

## 4. Open

- A real payment provider (keys, webhook) — by configuration later; the sandbox stays the default.
- SMS proof of phone — when an SMS provider is chosen, sign-up can verify the phone and orders by phone can attach.
- Payouts — MKT-1g.
