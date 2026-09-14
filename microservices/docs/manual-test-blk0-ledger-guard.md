# Manual test — BLK-0: the ledger write is internal-only and every payment is attributable

**Run as:** `owner.business@myplus.com` / `Demo@2025!` (cases 1–7), then `owner.education@myplus.com` /
`Demo@2025!` (case 8).
**Stack:** monolith `:8080`, gateway `:8765`, business, finance, audit, education — all must be up.
**Time:** ~15 minutes.
**Design:** `blocking-ui-and-backend-guards-design.md` §8.5 · **Automated gate:**
`cypress/e2e/security/finance-ledger-write-guard.cy.js`

> **What you are proving.** Money can only reach the ledger through the screens that allocate it properly,
> every payment says who took it, and none of that made the till slower or less reliable.

> ⚠ **Cases 1, 3, 4 and 5 are regression cases and they matter more than case 2.** BLK-0 changes the path the
> payment flow travels. A security fix that stopped cashiers taking money, or that let a double-click charge
> twice, would be far worse than the gap it closes. **If any of them fails, stop and report it — do not accept
> a pass on case 2 as success.**

---

## ⚠ Before you start — the deploy check

BLK-0 changes a shared library contract. **commerce-contracts, business, education and finance must all be
rebuilt and restarted together.** A service left on its old build still *looks* fine: the payment says
success and the balance moves — but the receipt never reaches the finance ledger, and nothing on screen
says so.

**How you will notice:** in Case 1 the success message must carry a **receipt number** (`RCPT-…`). A success
with a **blank** receipt number is this failure. Stop and rebuild; do not continue.

## Before you start — take a baseline

1. Open **Customers** and pick a customer who owes money.
2. Write down: customer name ......................  **due before** ......................

---

## Case 1 ⭐ — the front door still works

| Step | Do this | Expect |
|---|---|---|
| 1.1 | Customers → select your customer → **Receive Payment** | The payment dialog opens |
| 1.2 | Enter **100**, method **Cash**, click Receive | Success message **with a receipt number `RCPT-…`** |
| 1.3 | Note the receipt number and the new due | Due has dropped by exactly 100 |

Receipt no ......................  due now ......................

**Fails if:** any error; **or no receipt number** (see the deploy check); or the due did not move by exactly 100.

---

## Case 2 ⭐⭐ — the back door is shut

This is the actual fix. You are acting as an API client, which is what the gap exposed.

Open Git Bash and run (paste as one block):

```bash
TOKEN=$(curl -s -X POST http://localhost:8765/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner.business@myplus.com","password":"Demo@2025!"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

echo "token length: ${#TOKEN}"          # must be > 100, or the login failed

curl -s -o /dev/null -w 'HTTP %{http_code}\n' \
  -X POST http://localhost:8765/api/finance/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"partyType":"CUSTOMER","partyId":1,"direction":"RECEIPT","amount":999999,
       "paidOn":"2026-01-01","reference":"MANUAL-BACKDOOR-PROBE"}'
```

| | |
|---|---|
| ✅ **PASS** | `HTTP 405` (or 401 / 403 / 404) — the write is refused |
| ❌ **FAIL** | `HTTP 200` — a PKR 999,999 credit was just written with no allocation and no audit |

⚠ **If this returns 200, say so immediately.** It means the endpoint is still publicly routed.

⚠ The body is deliberately **valid** (`direction` is `RECEIPT`). An invalid one would be refused with `400`
even by an OPEN endpoint, and the test would pass for the wrong reason.

**Then confirm nothing landed:** re-open the customer from Case 1. The due must be **unchanged** from 1.3.

Due still ......................  (must equal 1.3)

---

## Case 3 ⭐⭐ — a double-click cannot charge twice

| Step | Do this | Expect |
|---|---|---|
| 3.1 | Open Receive Payment for a customer with a due | Dialog opens |
| 3.2 | Enter **50**, then **double-click Receive fast** | ONE success message |
| 3.3 | Look at the due | Dropped by **50**, not 100 |
| 3.4 | Open the customer's payment history / statement | **ONE** payment of 50, not two |

**Fails if:** the due drops by 100, or two payments appear. That is a double charge — stop everything.

---

## Case 4 ⭐ — a reload mid-payment records a GENUINE second payment

The case a disabled button cannot cover, and the reason §0c exists.

| Step | Do this | Expect |
|---|---|---|
| 4.1 | Open Receive Payment, enter **25**, click Receive | Success |
| 4.2 | **Press F5 / reload** the whole page | Page reloads |
| 4.3 | Open Receive Payment for the same customer, enter **25** again, click Receive | Success |
| 4.4 | Check the due and the history | **TWO separate payments of 25** — total 50 off the due |

⚠ **This one is SUPPOSED to charge twice.** After a reload the operator is making a genuinely new decision,
and the app must not silently swallow a second real payment. Case 3 proves an accidental repeat is blocked;
this proves a deliberate one is not. **If 4.4 shows only one payment, that is a defect** — money the shop
took and did not record.

---

## Case 5 ⭐ — supplier payments still work (the other side of the ledger)

| Step | Do this | Expect |
|---|---|---|
| 5.1 | Suppliers → pick one → **Pay Vendor** | Dialog opens |
| 5.2 | Enter **100**, click Pay | Success **with a voucher number `PV-…`** |
| 5.3 | Double-click Pay on a fresh payment of **30** | ONE payment of 30 recorded |

**Fails if:** an error, a blank voucher number, or a doubled amount. AP and AR share the mechanism.

---

## Case 6 ⭐ — every shop payment says who took it

| Step | Do this | Expect |
|---|---|---|
| 6.1 | Open **Audit Log** (owner-only screen) | It loads |
| 6.2 | Find the row whose reference is the **receipt number from Case 1** | Action **RECEIPT**, type **CUSTOMER** |
| 6.3 | Read the row | It names **who** (`owner.business@myplus.com`), **when**, and the **amount** (100) |

**Fails if:** no audit row for the receipt, or it names nobody.

ℹ The row comes from **business-service**, not finance. That is deliberate (design §8.5.1): the service that
takes the money records it; a second copy from the ledger would show every payment twice.

⚠ A **403 / "Only an owner…"** message means you are not logged in as the owner. That is the control
working, not a bug.

---

## Case 7 — the till did not get slower

| Step | Do this | Expect |
|---|---|---|
| 7.1 | Take three payments in a row, timing each from click to success | Each well under a second |
| 7.2 | While a payment is saving, click the nav or type elsewhere | The rest of the screen stays usable |

**Fails if:** the app freezes on save. BLK-0 must not reintroduce blocking — the trade PERF-13 rejected.

---

## Case 8 ⭐ — a school fee says who took it (log in as `owner.education@myplus.com`)

The one payer of the ledger that recorded **nobody** before this slice.

| Step | Do this | Expect |
|---|---|---|
| 8.1 | Register a student with a new enrolment number | Saved |
| 8.2 | Collect a fee of **1500**, Received In **Cash** | Success |
| 8.3 | In the same browser, open `http://localhost:8080/getAuditLog` | A page of JSON text |
| 8.4 | Search the page (Ctrl+F) for your enrolment number | A row with `"action":"RECEIPT"`, `"entityType":"STUDENT"`, `"amount":1500` |
| 8.5 | Read that row | `"actorEmail":"owner.education@myplus.com"` and `"sourceService":"education"` |

**Fails if:** no row for your enrolment number, or it names nobody.

ℹ Step 8.3 reads the raw trail because a school-side Audit Log **screen** has not been verified to exist.
Whether the fee also reached the finance LEDGER is covered by automated gate case 7, not by this walk.

---

## Result

| Case | Pass | Fail | Notes |
|---|---|---|---|
| Deploy check — receipt number present | ☐ | ☐ | |
| 1 — front door works | ☐ | ☐ | |
| 2 ⭐⭐ — back door shut | ☐ | ☐ | HTTP code: ......... |
| 3 ⭐⭐ — double-click safe | ☐ | ☐ | |
| 4 — reload records a real 2nd payment | ☐ | ☐ | |
| 5 — supplier side works | ☐ | ☐ | |
| 6 — shop audit names the taker | ☐ | ☐ | |
| 7 — no slowdown, no freeze | ☐ | ☐ | |
| 8 — school fee audit names the taker | ☐ | ☐ | |

**Any FAIL on 2 → the security gap is still open.**
**Any FAIL on the deploy check, 1, 3 or 5 → stop; the payment path is broken and that outranks the fix.**
