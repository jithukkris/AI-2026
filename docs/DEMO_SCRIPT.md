# 🎤 Demo script (about 4 minutes)

**Before you go on stage**

- Start fresh about a minute before you present, with the automatic loop **off** so you can trigger healing live:

  ```bash
  HEALING_AUTO=false DELAYED_SETTLEMENT_SECONDS=240 \
    mvn spring-boot:run -Dspring-boot.run.profiles=claude     # drop the profile for the offline version
  ```

  A fresh start gives predictable references: `TXN-100001` … `TXN-100007` are the incidents.
  The delayed settlement for `TXN-100007` lands about 4 minutes after startup.
- Open two tabs: **Dashboard** (`/`) and **Exceptions** (`/exceptions`). Sign in as `admin / admin123`.
- Backup: if the Wi-Fi dies, restart without the `claude` profile. Everything works offline with the rule-based AI.

---

### 0:00 · The problem (30 s)

> "Every payments company reconciles three sources: its ledger, the gateway and the bank. When they
> disagree (a lost webhook, a double charge, a truncated bank reference) someone fixes it by hand
> the next morning. HealPay fixes these continuously and safely."

Point at the **Detect → Diagnose → Decide → Act → Verify** strip on the dashboard.

### 0:30 · The mess (30 s)

- Go to **Transactions**. Everything is `UNRECONCILED`: 40 payments that nobody has checked yet.
- Open **TXN-100001** and show the three-way view: *gateway SUCCESS, ledger PENDING, bank credited*.
  The timeline shows `WEBHOOK_FAILED (HTTP 504)`.

### 1:00 · One click (60 s)

- Back to the **Dashboard** → **▶ Run healing now**.
- Watch the **live feed**: `DETECTED → DIAGNOSED (AI) → FIX_APPLIED → AUTO_HEALED`, with toasts popping up.
  With Claude this takes a few seconds per exception; the page redirects back when the cycle finishes.
- KPIs bump: **Auto-healed by AI** goes up, **Amount at risk** goes down.

> "Three issues fixed themselves in seconds: the missed webhook, a GST fee variance, and a bank credit
> whose UTR was dropped, matched by fuzzy reference."

### 2:00 · Explainability (45 s)

- Open **TXN-100004** (truncated UTR, strong match). Show the **AI box**: root cause, explanation,
  *confidence ~92%*, and the **policy line**: *"Safe action, confidence 0.92, amount within auto-heal limit."*
- Show the timeline: `DIAGNOSED` (AI) → `FIX_APPLIED` → `VERIFIED` → `AUTO_HEALED`.

> "Notice the AI didn't decide alone. It proposed; our policy engine allowed it; then we verified
> the books really balance."

### 2:45 · Human in the loop (45 s)

- **Dashboard → Needs your approval.** Show TXN-100002 (double charge, refund) and TXN-100006 (reversal).
  Policy says: *"moves money: human approval always required."*
- Show TXN-100005 (weak match): *"AI confidence 0.73 is below threshold 0.80."*
- Click **Approve** on the duplicate. The flash message reads *"Approved and healed… verified balanced."* Status → `HEALED`.

### 3:30 · Ask AI (20 s)

- Go to **Ask AI** and type: *"What is waiting for approval?"*, then *"Why is TXN-100007 not settled?"*

> "Answers come from tool calls on live data. The assistant can read, never write."

### 3:50 · Self-resolution and close (10 s)

- TXN-100007's settlement arrives (`SETTLED` from BANK). Click **Run healing now** once more: `VERIFIED`, with no AI and no human involved.

> "Safe fixes are automatic, risky ones are one click, and everything is explained and audited. That's HealPay."

---

### Likely judge questions

| Question | Answer |
|---|---|
| *What if the AI hallucinates?* | It can only pick from a closed list of actions per incident type; anything else is overridden to escalate. Preconditions are re-checked in Java, and every fix is verified by re-reconciling. |
| *Does the AI ever move money?* | No. Refunds and reversals always need a human. |
| *What does it cost to run?* | One small-model call per new exception (not per transaction). Clean payments never reach the AI. |
| *What happens if Claude is down?* | A rule-based diagnoser takes over automatically; the explanation is tagged so you know. |
| *How would it work with real data?* | Replace the simulator with gateway settlement and bank statement ingestion. The engine, AI and policy stay the same. |
