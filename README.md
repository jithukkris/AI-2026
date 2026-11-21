# HealPay AI — Self-Healing Payment Balancing System

> Payments break quietly: a webhook times out, a customer is charged twice, a bank drops the UTR.
> **HealPay AI** watches the ledger, payment gateway and bank feed continuously, uses **Claude (via Spring AI)**
> to diagnose every mismatch, **heals the safe ones automatically**, and asks a human only when money would move.

```
 DETECT ──▶ DIAGNOSE (AI) ──▶ DECIDE (policy) ──▶ ACT ──▶ VERIFY
 3-way recon   Claude explains     guardrails decide    idempotent   re-reconcile,
 rules engine  + proposes a fix    auto / approve / wait  healing     resolve or escalate
```

| | |
|---|---|
| **Backend** | Java 21 · Spring Boot 3.5 · **Spring AI 1.0** (Anthropic Claude) · Spring Data JPA · Spring Security |
| **UI** | Thymeleaf dashboard with live updates (Server-Sent Events) · Swagger UI |
| **Data** | H2 (zero-setup) or PostgreSQL (Docker) |
| **AI features** | Structured output · tool calling (read-only tools) · natural-language assistant · rule-based fallback |
| **Python?** | Not needed. Everything runs on the JVM. |

---

## Documentation

| Audience | Document |
|---|---|
| 🧑‍💻 Developers | [`docs/DEVELOPER_GUIDE.md`](docs/DEVELOPER_GUIDE.md) — architecture, code map, healing rules, AI integration, API, extending |
| 🏆 Hackathon judges / showcase | [`docs/HACKATHON.md`](docs/HACKATHON.md) — problem, solution, what's novel, impact, roadmap |
| 🎤 Live demo | [`docs/DEMO_SCRIPT.md`](docs/DEMO_SCRIPT.md) — a 4-minute click-by-click demo |
| ☁️ Hosting (free) | [`docs/DEPLOY-GCP.md`](docs/DEPLOY-GCP.md) — Google Cloud e2-micro + Cloudflare Tunnel |

---

## Quick start

### Option A — run with Maven (no Docker, no API key)

Requirements: **Java 21** and **Maven 3.9+**.

```bash
mvn spring-boot:run
```

Open <http://localhost:8080> and sign in as **`admin / admin123`** (or `viewer / viewer123`).
The app starts with 40 simulated payments, including one of every incident type, and the
AI runs in **rule-based mock mode**, so everything works offline.

### Option B — real AI with Claude

Get an API key from the [Claude Console](https://console.anthropic.com) (API usage is billed separately
from Claude.ai Pro/Max plans), then:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run -Dspring-boot.run.profiles=claude
```

The dashboard badge switches to **"AI: Claude (live)"**. If the API is unreachable, diagnosis
falls back to the rule engine automatically, so healing never stalls.

### Option C — Docker Compose with PostgreSQL

```bash
cp .env.example .env          # edit passwords; add ANTHROPIC_API_KEY and set
                              # SPRING_PROFILES_ACTIVE=postgres,claude for real AI
docker compose up -d --build
```

### Run the tests

```bash
mvn test
```

`SelfHealingFlowTest` injects one of every incident, runs a healing cycle and asserts that safe
issues healed while risky ones wait for approval.

---

## What you'll see

- **Dashboard** — KPIs (auto-heal rate, amount at risk), approvals queue, live activity feed.
- **Transactions** — search by `TXN-…`, merchant or gateway ref; each transaction shows a
  **three-way view** (ledger / gateway / bank) and a **status timeline** of every step.
- **Exceptions** — every mismatch with the **AI root cause, explanation, confidence and proposed fix**,
  plus the **policy decision** (why it was auto-healed or sent for approval). Admins approve or reject here.
- **Ask AI** — "Why is TXN-100003 still pending?" Answered with read-only tool calls against live data.
- **API** — `/swagger-ui.html` (all endpoints, HTTP Basic auth).

## Incidents the system handles

| Incident | Detected as | AI's usual fix | Policy |
|---|---|---|---|
| Success webhook lost | `STATUS_MISMATCH` | Sync status from gateway | **Auto** |
| GST charged on top of MDR | `FEE_VARIANCE` | Post fee adjustment | **Auto** (≤ limit) |
| Bank dropped UTR, narration has most of the ref | `MISSING_IN_BANK` | Link bank credit (fuzzy match) | **Auto** (high confidence) |
| Same, but only a fragment survives | `MISSING_IN_BANK` | Link bank credit | **Approval** (low confidence) |
| Customer charged twice | `DUPLICATE_DEBIT` | Refund duplicate | **Approval** (moves money) |
| Ledger booked success, gateway failed | `STATUS_MISMATCH` | Reverse ledger entry | **Approval** (moves money) |
| Settlement not in bank yet | `SETTLEMENT_DELAYED` | Wait and re-check | **Wait** → self-resolves |
| Unknown credit in bank | `ORPHAN_BANK_CREDIT` | Escalate | **Escalate** |

## Project layout

```
src/main/java/com/healpay
├── recon/        deterministic 3-way reconciliation engine + summary
├── ai/           Spring AI integration: Claude diagnosis & assistant, tools, rule-based fallback
├── healing/      policy (guardrails), executor (fixes), orchestrator (the loop)
├── simulator/    realistic gateway / bank / ledger data with injected incidents
├── domain/       JPA entities and enums
├── events/       audit trail + live SSE event bus
├── web/          Thymeleaf pages + REST API
└── config/       security, properties, formatting
```

## License

MIT, or whatever suits your hackathon. Add a `LICENSE` file before publishing.
