# shared-ledger

Self-hosted personal-finance application for a household of one or two. Replaces a spreadsheet workflow for monthly transaction tracking and periodic net-worth snapshots, with budgets, recurring templates, analytics, and a FIRE projection on top.

Single repository, two services orchestrated with Docker Compose:

- **backend** — Kotlin / Spring Boot 4 on JDK 24, Spring Security with cookie sessions, Spring Data JPA + Flyway, Spring Session JDBC, Micrometer + Prometheus, JSON logs.
- **frontend** — React 19 + TypeScript on Vite 7, TanStack Query for server state, react-i18next for localization, Tailwind 4 with in-house primitives, Recharts 3 for charts, axios for HTTP. Served behind nginx in production with `/api` proxied to the backend.

Postgres is **not** part of this compose file. It runs externally on a Docker network the operator manages.

---

## 1. What this application does

### Users and access

- Email + password authentication. No social login.
- 30-day sliding sessions stored server-side (Spring Session JDBC), HttpOnly+SameSite=Lax cookie.
- Password change requires the current password.
- Per-user preferred locale: English or Spanish.
- Login is rate-limited per source IP (5/minute, 20/hour by default).
- **Registration modes** (configurable via `REGISTRATION_MODE`):
  - `open` — anyone can register; a new user creates a new household and becomes its owner.
  - `invite-only` — registration requires a valid invitation token from an existing owner. Recommended default.
  - `closed` — bootstrap admin only.
- **Invitations**: household owners issue one-time tokens (raw token shown once at issue time, hashed in storage). 14-day default expiry. The invited user joins as `owner` or `member` per the invitation.
- **Bootstrap**: on first start with an empty users table, an admin user and initial household are created from `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `BOOTSTRAP_HOUSEHOLD_*`.

### Households

- All financial data belongs to a household, not directly to a user.
- A user may belong to one or more households.
- Each household has its own currency (default EUR) and default locale.
- Roles within a household: **owner** and **member**. Only owners may modify household settings, manage invitations, or delete the household.
- Every household-scoped API endpoint verifies the caller is a member of the household. Household IDs in paths are never trusted without that check.
- **Switching households**: when a user belongs to more than one household, a dropdown appears in the header to switch the active household. With a single household the dropdown is hidden.
- **Default household**: each user can pick one of their memberships as their default. On login (and on any new device), the app selects the default first; if none is set, the first membership is used. The default is stored per user and survives a clean browser.
- **Creating a household**: any authenticated user can create a new household from the header dropdown or from *Settings → Households*. The creator becomes its `owner`.
- **Deleting a household** (owner only, hard delete): permanently removes the household and every record cascaded from it (transactions, snapshots, movements, budgets, recurring templates, FIRE settings, invitations, memberships). Confirmation requires typing `delete`. The operation is **refused with `HOUSEHOLD_IS_DEFAULT`** if any user — yourself or anyone else — has it set as their default; pick a different default first, then delete.

### Categories (fixed, seeded)

Income (flat list, no group): `Salary`, `Pension`, `Reimbursements`, `Benefits`, `Financial income`, `Other income`, `Transfers`.

Expense categories grouped by area:

- **Home** — Rent, Mortgage, Utilities, Insurance & fees, Services, Taxes, Repairs, Furniture, Other.
- **Transport** — Fuel, Public transport, Airfare, Car maintenance, Parking, Other.
- **Groceries** — Groceries.
- **Shopping** — Clothing, Electronics, Gifts, Other shopping.
- **Outings** — Restaurants & bars, Travel, Subscriptions, Hobbies, Lottery tickets.
- **Financial** — Fees.
- **Health** — Medical, Pharmacy.
- **Personal** — Personal care, Education, Other.

Categories have stable codes used in the API and database. Display labels live in the frontend translation files. Adding a language never requires a backend change. Adding categories is a Flyway migration; there is no UI for category management.

Investment, debt-payment and similar capital flows are intentionally NOT categories — they're handled as **net worth movements** (see below).

### Transactions

The day-to-day workflow. The operator and partner record real income and expenses as they happen, often from a phone.

- Fields: occurrence date, amount (positive), direction (income or expense), category, optional description, who created it, who last updated it.
- Direction must match the category's kind (enforced by a database trigger + application check).
- Exact decimal arithmetic with two decimals end to end. No binary floats anywhere in the money path.
- Soft delete: deleting marks the row removed but preserves it. Lists and aggregations ignore deleted rows by default.
- List view supports filtering by date range, direction, category and category group, with pagination and sorting.
- **Quick-add** form optimized for mobile: numeric-keyboard amount, category picker with search, optional description, large save action. The categories the current user used most in the last 30 days surface as one-tap chips.
- Transactions generated by the recurring engine carry a reference to their template; they can be filtered and individually edited or deleted without affecting the template.

### Recurring templates

Templates that automatically generate transactions on schedule (rent, salary, subscriptions, insurance).

- Per template: direction, category, amount, optional description, cadence (weekly / monthly / yearly), the appropriate day field, start date, optional end date, active flag, audit fields.
- **Cadence fields**:
  - `weekly` → `dayOfWeek` (1=Mon … 7=Sun).
  - `monthly` → `dayOfMonth` (1–31). Days beyond the month length are clamped to the last day (e.g. day 31 in February becomes Feb 28 or 29).
  - `yearly` → `monthOfYear` (1–12) + `dayOfMonthYearly` (1–31, same clamping).
- A **daily background job** (cron `0 0 2 * * *` UTC by default) materializes pending transactions up to today. Idempotency is enforced by a unique partial index on `transactions(recurring_template_id, occurrence_date)` — re-running the job never produces duplicates. Individual templates can also be triggered on-demand from the UI.
- Generated transactions can be edited or deleted individually without affecting the template.
- Deactivating a template stops future generation but does not touch past transactions.
- Templates show **last-fired** and **next-fire** dates in the list view.

### Budgets

Planned monthly (and optionally annual) expense amounts per category, compared against reality.

- For each `(household, year, month, category)` an expected amount can be set.
- Annual budgets are also valid (`year + category`, no month) for items that vary monthly but have an annual ceiling.
- Audit fields (`createdBy`, `updatedBy`) are stamped on every change.
- **Current-month dashboard**: for each budgeted category, show budget, spent so far, percent consumed, with color coding (on track / approaching / over).
- **Extrapolated pace**: at the current rate, where the month will end. Days elapsed/remaining visible.
- **Annual matrix view**: rows = expense categories, columns reflect the annual budget per category.

### Net worth snapshots

Periodic recordings of the value of each asset class and the outstanding balance of each liability. Snapshots are the source of truth for current net worth.

- Each snapshot has a date and an optional note. Multiple per month are allowed; monthly views use the latest one per month.
- **Asset classes** are fixed: `Cash`, `Index funds`, `ETFs`, `Stocks`, `Crypto`, `Pension`. A snapshot stores one value (defaulting to 0) per class.
- **Liabilities** are user-defined named entities (e.g. "Archena mortgage", "Car loan") with an active flag. Every active liability appears in the snapshot form for entering its outstanding balance.
- The snapshot creation form **prefills from the previous snapshot** and shows delta (absolute and percent) next to each input. If any delta exceeds 50% the form requires explicit confirmation before saving — a typo guard.
- **Evolution view**: stacked area chart of assets by class over time, with a net worth line (assets minus liabilities) overlaid. When movements exist, a dashed cumulative-contributions line is overlaid as well; the gap between it and the net-worth line is the revaluation (gain or loss), surfaced in the tooltip in both absolute terms and as a percent of contributions.
- **Allocation view**: pie chart by asset class at the latest snapshot.
- Queries support a date range or an "as of" date.

### Net worth movements

A separate, dedicated workflow for recording capital movements (contributions to investments, withdrawals from investments, principal payments on debt). Kept deliberately apart from the day-to-day transactions log because these are reallocations of capital, not operational income or expenses.

- Each movement has: date, type (`contribution` / `withdrawal` / `debt_payment`), target (an asset class for contributions and withdrawals, a liability for debt payments), amount (positive), optional description, audit fields.
- A contribution to an asset class represents new capital flowing into that class (e.g. a DCA purchase of ETFs).
- A withdrawal represents capital leaving a class (e.g. selling stocks).
- A debt principal payment reduces the outstanding principal of a specific liability.
- Movements live in their own tab in the net worth section, distinct from transactions.
- **Movements do NOT alter snapshot values.** Snapshots remain the source of truth for current value. Movements enrich the analytical picture by separating capital flow from market return.
- A SQL CHECK constraint enforces the type/target combination at the database level.

What movements unlock:

- Cumulative contributions per asset class over any period.
- Real return per asset class = current snapshot value − net contributions since inception (or since any chosen start date).
- Investment cash-flow view by month or year.
- Principal repayment history per liability.
- On the FIRE chart: an optional cumulative-contributions overlay, making clear how much of net-worth growth is savings vs. market.

If the operator chooses not to record movements, the app remains correct (net worth via snapshots still works); they only lose the contribution-vs-return decomposition.

### Analytics

Beyond raw lists, the app provides comparisons and projections.

The Dashboard (landing page) shows the current month and current year at a glance and adds two at-a-glance tiles in a hero row:

- **Trailing 12-month savings rate**: primary number is the trailing-12-month rate; below it, the year-to-date and current-month rates. Beside the primary number, a small sparkline plots the trailing-12 rate month by month over the last 24 months so the trend is visible without opening Analytics.
- **Fixed cost of living**: trailing-12-month average of recurring (template-backed) expenses, expressed as €/day (÷30.44) and €/year (×12). A secondary line shows the same metrics including discretionary expenses for comparison. If fewer than 12 months of data exist, the average is taken over the months available and that count is shown.

Inside the **Analytics** section, the existing four tabs (Year-over-year, Year-by-year, Trailing 12 months, Forecast) are joined by four exploration tabs:

- **Year-over-year same-month comparison**: pick a month; compare totals and per-category breakdown across the last N years for which data exists.
- **Year-by-year comparison**: pick years; show monthly income / expenses / savings as one line per year over a 12-month X axis.
- **Trailing 12 months**: income, expenses, savings rate over the last 12 months.
- **Forecast**: per category, project the next 1–12 months from historical data. Method: simple moving average over a configurable window (3 / 6 / 12 months). For categories backed by an active recurring template, the template amount is used instead of the historical average (more accurate). Historical context is shown alongside the projection so it's interpretable.
- **Allocation**: donut chart of how income for the selected month or calendar year was split across expense groups (Home, Transport, Groceries, Shopping, Outings, Financial, Health, Personal), plus a "Saved" slice for the positive remainder. A ranked table below repeats the figures for exact reading.
- **Top movers**: top-5 increases and top-5 decreases in category spending for the selected month against a chosen baseline — same month last year, or trailing 6-month average. Categories absent from the baseline but with spending in the period surface separately as "New activity".
- **Recurring vs discretionary**: share of expenses generated by an active recurring template versus the rest, for a selected scope (current/past month, trailing 12, year-to-date, or any calendar year).
- **Heatmap**: a category-by-month matrix where each cell's color intensity reflects spending. Colour is scaled per row so categories with very different magnitudes (e.g. Mortgage vs Books) are both readable. Range is configurable (12, 24, 36, 48 months, or full history); a toggle swaps the matrix to income categories; clicking a cell drills into the transactions list pre-filtered by that category and month.

### FIRE projection

Long-term wealth projection toward a financial independence goal.

Per-household settings: target amount, target year, expected monthly contribution, configurable expected annual return scenarios (e.g. 4, 6, 8 %), and which asset classes count toward the goal (e.g. exclude operating Cash if desired).

Projection output:

- Year-by-year projected portfolio value for each scenario, starting from the most recent snapshot's qualifying asset total. Capitalization is annual; monthly contributions accumulate within each year.
- Chart shows one line per scenario plus a horizontal target line.
- Summary table: which year each scenario reaches the target (or "not within projection window").
- If at least 12 historical snapshots exist, the operator's **actual annualized return** is computed from the snapshot series and shown as a reference.
- If net worth movements exist, an optional cumulative-contributions overlay distinguishes savings from market growth.

### Internationalization

- English and Spanish supported throughout the UI.
- Server-side messages (validation errors, auth errors, generic API errors) are also localized.
- Locale resolution order: `Accept-Language` header → authenticated user's preferred locale → English.
- Currency, date and number formatting reflect the active locale and the household currency.
- Language toggle in the header persists per user.

### Settings

- Change language (per user).
- Change password.
- **Households**: list every household the user belongs to, mark one as default, switch the active household, create a new household, or hard-delete an owned household (subject to the default-household restriction described above).
- Household name, currency and default locale (owners only).
- Liabilities management: name + active flag (full CRUD).
- Members & invitations: issue and revoke invitations, see pending ones (owners only).
- FIRE settings (owners only).

---

## 2. Non-functional behavior

### Security

- Cookie-based session authentication: `HttpOnly`, `Secure` (toggleable for non-HTTPS local dev via `APP_COOKIE_SECURE`), `SameSite=Lax`.
- CSRF protection enforced on every state-changing request. The frontend seeds the `XSRF-TOKEN` cookie via `GET /api/auth/csrf` on app load and forwards it as `X-XSRF-TOKEN` automatically.
- All state-changing endpoints require authentication; all household-scoped reads enforce membership.
- Passwords hashed with **Argon2id** via Spring Security's `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`.
- Login attempts rate-limited per source IP via Bucket4j (in-memory).
- No secrets in source. All credentials, admin bootstrap values, and runtime config come from environment variables.

### Data integrity

- All monetary values use exact decimal types end to end: `NUMERIC(15,2)` in Postgres, `BigDecimal` on the JVM, JSON strings on the wire, `decimal.js` on the frontend. No binary floats in the money path.
- Day-precision dates use `DATE` columns, not `TIMESTAMP`.
- Soft delete is observed transparently. Any aggregation or list of "transactions" means non-deleted unless explicitly stated.
- Database constraints back up application-level validation: foreign keys, check constraints, partial unique indexes for soft-delete-aware uniqueness, and SQL triggers for cross-table invariants (e.g. category direction).

### Observability

- **Structured JSON logs** to stdout, with `timestamp`, `level`, `logger`, `thread`, `message`, and MDC fields: `requestId`, `userId`, `householdId`, `templateId` (scheduler), `clientIp`.
- **Prometheus metrics** at `/actuator/prometheus` on a separate management port (9090), reachable only on the internal Docker network (not published to host).
- **Standard metrics**: JVM (heap, GC, threads), HTTP requests (rate, latency, errors), HikariCP (active/idle/wait), Spring Security login events.
- **Custom business metrics**:
  - `sl_transactions_created_total{direction, category_group}`
  - `sl_movements_created_total{movement_type, target_class, target_liability_id}`
  - `sl_snapshots_created_total`
  - `sl_recurring_materialized_total{template_id}` and `sl_recurring_materialization_failures_total{template_id}`
  - `sl_login_attempts_total{outcome=success|failure|rate_limited}`
  - `sl_active_sessions` (gauge)
  - `sl_registrations_total{mode, outcome}`
  - `sl_invitations_issued_total{role}`, `sl_invitations_accepted_total{role}`
  - `sl_analytics_request_seconds{endpoint}` (timers for month, year, year-over-year, year-by-year, forecast, dashboard_extras, allocation, top_movers, recurring_share, heatmap, contribution_series, and FIRE projection)
- **Health probes**: `/actuator/health/liveness` and `/readiness`.
- Grafana/Prometheus/Loki stack is **not** part of this repo. The app exposes the data; the operator wires up their own monitoring against the endpoints.

### Operations

- A background scheduler runs daily; failures are isolated per template, logged with `templateId` MDC, and counted in failure metrics. The app does not crash.
- All container logs use the `json-file` driver with rotation (10MB × 5).

---

## 3. Prerequisites

- Docker Engine with Compose v2 (`docker compose ...`, no `version:` key).
- A running Postgres reachable on a shared Docker network.
- The shared Docker network already exists (`docker network create infra_shared`, or whatever name you choose).

For local development outside Docker:

- JDK 24 (Eclipse Temurin recommended) and Gradle 9.5.1+
- Node.js 24+
- A reachable Postgres instance

---

## 4. Prepare the database (one-time, on the external Postgres)

Connect to Postgres as a superuser, then run:

```sql
CREATE USER sharedledger WITH PASSWORD 'choose-a-strong-password';
CREATE DATABASE sharedledger OWNER sharedledger;
GRANT ALL PRIVILEGES ON DATABASE sharedledger TO sharedledger;

\c sharedledger
GRANT ALL ON SCHEMA public TO sharedledger;
```

The application owns its schema via Flyway and creates the `pgcrypto` extension on first migration, so the user needs schema privileges (which the above provides).

Ensure the Postgres container is attached to the same Docker network the backend will join, and that it is reachable by the hostname configured in `DB_HOST` (e.g. as a service name or container alias).

---

## 5. Configure environment

```bash
cp .env.example .env
```

Edit `.env` and set at minimum:

| Variable | Notes |
| --- | --- |
| `SHARED_NETWORK_NAME` | External Docker network where Postgres lives. |
| `FRONTEND_PORT` | Host port to publish on `127.0.0.1` (operator's nginx fronts this). |
| `DB_*` | Match what was created in step 4. |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | First-run bootstrap admin. Used only if the users table is empty. |
| `BOOTSTRAP_HOUSEHOLD_*` | First household details. |
| `REGISTRATION_MODE` | `open`, `invite-only` (recommended), or `closed`. |
| `APP_COOKIE_SECURE` | `true` in production. Set to `false` only when running on plain HTTP for local dev. |

The `.env` file is git-ignored. `docker compose` reads it implicitly and the backend reads variables from it (via `env_file`).

---

## 6. Start the application

```bash
docker compose up -d --build
```

What happens on first boot:

1. Flyway applies all migrations against the configured database.
2. The Spring Session JDBC schema (`SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`) is created alongside the application's own tables.
3. The bootstrap runner sees an empty `users` table and creates the admin user + initial household from `ADMIN_*` and `BOOTSTRAP_HOUSEHOLD_*`.
4. Backend exposes the application on the internal network at `backend:8080`, and Prometheus metrics at `backend:9090/actuator/prometheus` — the management port is **not** published to the host.
5. Frontend nginx serves the SPA and reverse-proxies `/api` to the backend.

Open `http://<host>:${FRONTEND_PORT}` (defaults to port `8087`) and log in with `ADMIN_EMAIL` / `ADMIN_PASSWORD`.

### Putting Nginx Proxy Manager (or any reverse proxy) in front

The frontend service joins both the local `ledger` network and the shared external network (`${SHARED_NETWORK_NAME}`), and publishes its HTTP port on the host. Two ways to point NPM at it — pick whichever fits your setup:

**Path A — over the Docker network (recommended if NPM is in a container on this host).** Attach NPM to the same `${SHARED_NETWORK_NAME}` network. Then in NPM's Proxy Host config, set the **Forward Hostname / IP** to `shared-ledger-frontend` and **Forward Port** to `80`. No host port needs to be reachable from outside; you can set `FRONTEND_BIND_ADDR=127.0.0.1` in `.env` to keep the host port loopback-only as defense in depth.

**Path B — over the host's published port (use this if NPM lives on the host or on a different machine).** Leave `FRONTEND_BIND_ADDR=0.0.0.0` in `.env` so the published port answers on all host interfaces. In NPM set **Forward Hostname / IP** to the host's IP (or `host.docker.internal` if NPM runs in Docker on the same host) and **Forward Port** to `${FRONTEND_PORT}`. Lock the port down at the OS firewall so only NPM (and your own admin IPs) can reach it.

In both paths, NPM terminates TLS and forwards `X-Forwarded-For`, `X-Forwarded-Proto`, and `Host`. The backend trusts these headers (`server.forward-headers-strategy=framework`), so the session cookie's `Secure` flag and the rate limiter's per-IP keying work correctly behind the proxy. Keep `APP_COOKIE_SECURE=true` in `.env` for external access.

---

## 7. First-time verification recipe

After step 6:

1. **Log in** as the bootstrap admin. The dashboard renders empty.
2. **Add a transaction**: `/transactions` → *Quick add* → expense, today's date, an amount, a category. Confirm it appears in the list.
3. **Create a recurring template**: `/recurring` → *New template* → monthly rent on day 1. Hit *Materialize now*. Confirm the corresponding transactions for the months that already passed appear in `/transactions`. Click *Materialize now* again — the count must not change (idempotency).
4. **Set a budget**: `/budgets` → edit the "Groceries" row for the current month, save, then verify the *Month summary* table shows it.
5. **Take a net-worth snapshot**: `/networth` → *Snapshots* → *New snapshot* → values for every asset class, save. View *Evolution* and *Allocation*.
6. **Record a net-worth movement**: `/networth` → *Movements* → *Create* → contribution to ETFs.
7. **Configure FIRE**: `/fire` → set target, contribution, scenarios. View the projection chart.
8. **Invite a partner** (owners only): `/settings` → *Members & invitations* → *Issue invitation*. Copy the link `…/register?invite=<token>` and open it in another browser session.
9. **Check observability**:
   - `docker compose logs -f backend` shows JSON logs with `requestId`, `userId`, `householdId` populated.
   - From a container on `${SHARED_NETWORK_NAME}`: `curl http://shared-ledger-backend:9090/actuator/prometheus` returns metrics, including `sl_transactions_created_total`, `sl_snapshots_created_total`, `sl_login_attempts_total`, `sl_active_sessions`, `sl_recurring_materialized_total`.
10. **Switch language**: top-right language picker → ES. Server validation messages localize on subsequent requests via `Accept-Language`.

---

## 8. Operating notes

- **Daily scheduler.** The recurring materializer runs daily at 02:00 UTC (configurable via `app.scheduler.recurring-cron`). The unique partial index on `transactions(recurring_template_id, occurrence_date)` enforces idempotency; reruns never duplicate. Failures don't crash the app — they're logged with `templateId` MDC and counted in `sl_recurring_materialization_failures_total`.
- **Sessions.** 30-day sliding sessions stored in `SPRING_SESSION`. Cookie `SESSION`, `HttpOnly`, `SameSite=Lax`, `Secure` controlled by `APP_COOKIE_SECURE`. Active session count is exposed as `sl_active_sessions`.
- **CSRF.** The frontend reads the `XSRF-TOKEN` cookie and sends `X-XSRF-TOKEN` on every state-changing request. The frontend hits `/api/auth/csrf` to seed the cookie on app load and on logout.
- **Rate limiting.** Login attempts are limited per source IP via Bucket4j in-memory (5/min, 20/hour by default). State resets on restart — acceptable for a self-hosted single replica.
- **Logs.** JSON to stdout, rotated by Docker (`json-file`, 10MB × 5).
- **Backups.** Postgres is the only durable state; back up the `sharedledger` database with your usual tooling.

---

## 9. Local development (no Docker)

Backend:

```bash
cd backend
# Ensure DB_* env vars point to a real Postgres
APP_COOKIE_SECURE=false ADMIN_EMAIL=dev@example.com ADMIN_PASSWORD=devdevdev \
  gradle bootRun
```

Frontend:

```bash
cd frontend
npm install
npm run dev
# Visit http://localhost:5173 — Vite proxies /api to http://localhost:8080
```

Tests:

```bash
cd backend && gradle test       # Testcontainers spins up Postgres
cd frontend && npm test
```

---

## 10. Project layout

```
shared-ledger/
├── backend/
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/main/{kotlin,resources}/...
├── frontend/
│   ├── package.json
│   ├── Dockerfile
│   ├── nginx.conf
│   └── src/...
├── docker-compose.yml
├── .env.example
└── README.md
```
