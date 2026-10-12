# Runbook — team members and the platform admin on production (maxtheservice.com)

2026-10-12 · for: the owner of each business on maxtheservice.com, and whoever runs the server.
Scope: how a team member (a doctor, the reception desk, a pharmacist) gets a login and signs in for the first time,
and how `admin@myplus.com` differs. No step here needs SQL or server access except §2, which is a check.

---

## 1. Two kinds of account

| | Team member (Doctor / Clinic reception / Standard / …) | `admin@myplus.com` |
|---|---|---|
| What it is | A person who works in ONE business | The platform account (its own "Default Admin's organization") |
| Who creates it | That business's owner (or one of its admins) on **Team** | auth-service, by itself, at start-up — only if it does not exist yet |
| Password | Nobody sets it for them. They get an e-mail and choose their own | `APP_ADMIN_PASSWORD` in the server's `.env`, read at the FIRST start only |
| Where it lives | Production's database. Accounts made on a laptop do **not** travel to production — a deploy never copies accounts | Same |

⚠ The three accounts made on 2026-10-12 (uncer_sh@yahoo.com, email2uncer@gmail.com, pharmacist@gmail.com) were made
on the **local** stack, in the demo business "Owner Pharma's organization". They do not exist on production; make them
there with §3.

## 2. Before the first invitation — server check (once)

Read-only checks run 2026-10-12 11:48 UTC against https://maxtheservice.com (pages loaded, no form sent):

| Check | Result |
|---|---|
| `/login` loads, has **Forgot password?** (`#loginForgot`) | ✅ 200 — present |
| `/login?message=…` shows the message bar | ✅ |
| `/forgetPassword.html` loads, e-mail field, posts to `user/resetPassword` | ✅ 200 |
| `/user/changePassword?token=…` loads, form + token field, no broken button handler | ✅ 200 — fixed build is live |
| Login assets carry a content hash (`login-a41dcf30….css`), so no stale styling | ✅ |
| reCAPTCHA on login / forgot password | ⚠ **off** (no widget on either page) — see §6 |
| Monolith build | assets dated 2026-10-12 10:50 UTC (today's fixes are live) |

**Not checkable from outside — the server's `.env` must say** (on the VPS, `microservices/.env`; there must be **no**
`microservices/.env.local` on the VPS):

```
RESET_PASSWORD_URL=https://maxtheservice.com/user/changePassword   # the link inside the e-mail
APP_BASE_URL=https://maxtheservice.com
MAIL_HOST=smtp.gmail.com     # a REAL mail server — NOT mailpit (mailpit catches mail and nobody receives it)
MAIL_USERNAME / MAIL_PASSWORD = the sending Gmail account and its app password
APP_SEED_DEMO=false          # demo businesses are local only
```

Proof it works end to end = §3 with **your own** address first: if your e-mail arrives and its link opens
maxtheservice.com, the server is right.

## 3. Add a team member (the business owner, on the screen)

1. Sign in at https://maxtheservice.com as the **owner** of the business the person works for.
2. Menu **Team → Team**.
3. First name, Last name, **their real e-mail** (one they read), Role **USER** (ADMIN only for a manager); tick the
   stores, or leave All. Press **Add member**.
   - Expected: "Team member created — a set-password email was sent."; the person is listed, Active.
4. On their row, in **Permission set**, choose the set:
   - **Doctor** — sees patients; never sells or dispenses.
   - **Clinic reception** — registers patients, tokens, takes payment; does not dispense.
   - **Standard** — selling and dispensing (the pharmacist).
   - Expected: a green "saved" message; the row keeps the choice after a refresh. The change reaches someone already
     signed in within 15 minutes (or at their next sign-in).

## 4. First sign-in (the person, from their own inbox)

1. Open the e-mail **"MyPlus - Password Reset"** (check spam/promotions).
2. Click the link — it opens `https://maxtheservice.com/user/changePassword?token=…`. **It works once, for 1 hour.**
3. Type a password twice; press **Update Password**.
   - Expected: the login page with "Password reset successfully".
4. Sign in with the e-mail and that password.

Link expired, lost, or the e-mail never came → on the login page press **Forgot password?**, enter the e-mail, and use
the NEWEST e-mail.

## 5. When it does not work

| Symptom | Cause | Fix |
|---|---|---|
| No e-mail at all, even for your own address | server mail not configured (§2) or still pointing at mailpit | server operator: §2 values, then redeploy auth-service with `deploy.ps1` |
| E-mail arrives, link opens `localhost` | the VPS has an `.env.local`, or `.env` has local addresses | remove it / fix §2, redeploy auth-service |
| "Invalid token." | link already used, or more than 1 hour old (both show the same message) | Forgot password? → newest e-mail |
| "Invalid Credentials" before a password was ever set | expected — nobody knows the first password | §4 |
| Forgot password? says an e-mail is on its way, but none ever comes | the address is not a member on THIS server — the page says the same either way, on purpose, so nobody can probe which addresses exist (a mail failure looks the same) | check the Team list (§3); if they are listed, see the first row |
| Signed in but a menu is missing | wrong permission set, or the change is < 15 min old | §3 step 4; sign out and in |
| Typed the wrong e-mail on Team | no Remove member yet (open item) | add again with the right e-mail; tell the server operator about the wrong one |

## 6. Safety rules and open items

- **Use addresses the person owns.** `pharmacist@gmail.com` almost certainly belongs to a stranger: whoever reads that
  inbox can set the password and enter the business. Use the pharmacist's real address.
- **admin@myplus.com:** `APP_ADMIN_PASSWORD` is read only when the account is first created; changing it later does
  NOT change the password. Recovering it by "Forgot password?" sends the link to the **myplus.com** mailbox — make sure
  that domain's mail is yours before relying on it (not verified). Keep the admin password in a password manager.
- **Open (not done):**
  - reCAPTCHA is off on Forgot password, so anyone can make the server send reset e-mails to any address; turn it on for production (`app.captcha.enabled` + keys).
  - Each Forgot password? adds a new token while the older ones stay valid until they expire; one local test account had 56. The new one should cancel the old.
  - The invitation is a 1-hour "Password Reset" e-mail; there is no Resend or Remove member on Team.
