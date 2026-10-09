# EX-5 — Receipts

**Status:** DONE 2026-10-09 — gate 6/6 (repeatable), unit 9 new (expense module 65/65 incl. Flyway V8 on MySQL), Test
Book cases 5-1…5-6 (recorded; they replaced the single case 1-11). Programme: [`../expense-management-design.md`](../expense-management-design.md) §5.7 `ReceiptStore`, §6.2
`receipt.requiredAbove`, §10 EX-5; ruling **R-3** (2026-10-09): kept on the server, compressed on the device.

## 1. Document
An expense had no evidence behind it. The design's EX-5: a `ReceiptStore` port (local disk now, S3 later), type + size +
sha256, an audited read, the owner's "required above" rule and a duplicate warning.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Storage | Blobs never in MySQL: the `ReceiptStore` port; `LocalFsReceiptStore` on the named volume `myplus-expense-receipts` (written to a temp file and moved into place; a key leaving the base directory is refused). S3 adapter later, no service change |
| What is accepted | JPEG/PNG/WEBP/PDF recognised by their FIRST BYTES (never the name or the browser's type), ≤ 6 MB. The screen shrinks photos (long side 1600 px, JPEG 0.82, transparent → white) before sending; HEIC is refused on the device with what to do |
| Upload first, attach on save | the save carries `receiptIds` and attaches them in its own transaction — so "required above X" is enforced where the money is recorded. A receipt can also be added to an expense already saved |
| Same file twice | sha256: again for the same expense (or, unsaved, by the same person) → the same receipt; on ANOTHER (not voided) expense → named, and the screen asks before saving — every time Save is pressed |
| Scope | read through its expense: another tenant's or (for a user) a colleague's → 404; unsaved → its uploader only. Viewing is audited (`RECEIPT_VIEWED`); served with the type WE recognised, `nosniff`, `private, no-store` |
| Remove | owner/admin, soft (`removed_at`), the file kept; uploads never attached within a day are swept (they were never evidence) |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| Builders of `VoucherRequest` in Java | 0 | JSON only — adding `receiptIds` is additive |
| `VoucherView` constructions | 1 (`of`) | + `withReceipts(n)`; the list fills counts in ONE query per page |
| `ExpenseVoucherService` constructor callers | 2 tests | + `ReceiptService` — injected directly: ReceiptService does not depend on it, so there is no cycle (a first draft used an ObjectProvider "to keep the graph simple" — a plausible, false comment; removed) |
| Upload hops and limits | 3 | monolith (dev 1 MB default → 10 MB like prod), gateway (streams), expense-service (6 MB) |
| Container user vs volume | 1 | the image runs as `app`; a fresh volume on a missing path is root-owned → the Dockerfile creates `/data/receipts` owned by `app` (verified writable) |
| CSRF on a FormData POST | 1 | stamped by the global `ajaxSend` hook (verified by the gate) |
| `sha256` column type | 1 | VARCHAR(64) to match the String field under `ddl-auto=validate` |

## 4. Gate — `cypress/e2e/expense/ex-5-receipts.cy.js` (school tenant)
1. A 2400×1800 photo saved with the expense: Receipts (1); stored smaller; served as a real JPEG with nosniff/no-store.
2. The same PDF on a second expense: warned with the first's number; Cancel saves nothing; Confirm saves; "Also on".
3. Required above 100: 150 without a receipt refused in words; with one, saved (after the duplicate warning).
4. HTML named .jpg: refused on the device and by the server.
5. A receipt added to an expense already saved.
6. Security: colleague 404, another business 404/400, user remove 403; removed → gone (404) and the row says "Add a receipt".

## 5. As built — what the gate found
- **Viewing a receipt answered 500:** `content()` was a read-only transaction but writes the audit row ("Connection is
  read-only"). The unit tests mock the audit and could not see it. Fixed.
- After Cancel on the duplicate warning, Save reused the upload and skipped the warning; it now warns every time.
- The gate depended on earlier runs' leftovers (a run that stopped half-way left a receipt on a live expense); `before()`
  now voids them, and a voided expense's receipt is not a duplicate.
- **Process note:** this gate was written before the code was deployed but was not run red first — the session's worker
  restarted between writing and deploying. Each case fails without the code by construction (no endpoint, no field).
- Backups: `backup-db.sh` covers MySQL; the receipts volume must be backed up with it (follow-up: add it to the script).
