# shared-ledger

Self-hosted personal-finance application for a household of one or two. Replaces a spreadsheet workflow for monthly transaction tracking and periodic net-worth snapshots, with budgets, recurring templates, an investment portfolio with automatic pricing, loan tracking, analytics, and a FIRE projection on top.

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

### Categories

Two tiers: a global, seeded catalog shared by every household, plus per-household **custom categories** managed by the owner.

**Global catalog (seeded, immutable).** Income (flat list, no group): `Salary`, `Pension`, `Reimbursements`, `Benefits`, `Financial income`, `Other income`, `Transfers`. Expense categories grouped by area:

- **Home** — Rent, Mortgage, Utilities, Insurance & fees, Services, Taxes, Repairs, Furniture, Other.
- **Transport** — Fuel, Public transport, Airfare, Car maintenance, Parking, Other.
- **Groceries** — Groceries.
- **Shopping** — Clothing, Electronics, Gifts, Other shopping.
- **Outings** — Restaurants & bars, Travel, Subscriptions, Hobbies, Lottery tickets.
- **Financial** — Fees.
- **Health** — Medical, Pharmacy.
- **Personal** — Personal care, Education, Other.

Global categories have stable codes used in the API and database. Display labels live in the frontend translation files. Adding a language never requires a backend change. Changing the global catalog is a Flyway migration.

**Custom categories (per household, owner-managed).** Owners can extend their household's catalog from *Settings → Custom categories*. Once created, a custom category behaves identically to a global one — it appears in the transaction picker, in budgets, in recurring templates, and in analytics (including the essential / discretionary split).

- An expense custom must belong to one of the eight global expense groups; income customs are flat, like the global income.* set. The group dictates the icon — customs don't define their own.
- The owner types a free-text name; the server derives a stable code as `{group}.{slug}` (or `income.{slug}`), where the slug is the name normalized to lowercase ASCII with underscores. Uniqueness is enforced per household, and the code must not collide with any global category.
- The owner picks whether the category counts as essential at creation time (defaults to off). Essential customs roll into the cost-of-living essential breakdown alongside essential globals.
- Customs are **private to the household** that created them; the API never returns one household's customs to another.
- Regular members see custom categories and can assign them to transactions, but cannot create, edit, or delete them.
- Edits cover renaming, toggling essential, and moving an expense custom between groups. The internal code is fixed at creation and never changes, so existing transactions, budgets, and recurring templates keep working across renames and group moves.
- **Deleting a custom is a hard delete with cascade.** The category row and every transaction, budget, and recurring template referencing it are permanently removed in one transaction. The UI requires the owner to type `delete` to confirm, regardless of how many rows would be affected. Deleting the household itself cascades the same way.

Investment, debt-payment and similar capital flows are intentionally NOT categories — they're handled as **net worth movements** (see below).

### Transactions

The day-to-day workflow. The operator and partner record real income and expenses as they happen, often from a phone.

- Fields: occurrence date, amount (positive), direction (income or expense), category, optional description, who created it, who last updated it.
- Direction must match the category's kind, enforced in the catalog service for both global and custom categories.
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
- **Portfolio prefill**: market asset classes (crypto, ETFs, stocks, funds) are prefilled from the [Portfolio](#portfolio-investments) at the snapshot date and marked *computed*; editing a value marks it *overridden*, and a class with no priced holdings is *carried over* from the previous snapshot. A per-holding valuation is frozen into each snapshot for audit. Snapshots remain the source of truth — the prefill only removes the manual tallying.
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

### Portfolio (investments)

Individual market holdings — crypto, ETFs, individual stocks, and funds — tracked at the lot level with automatic pricing. Portfolio sits alongside snapshots, movements, liabilities and loans as sibling tabs under the **Wealth** hub (*Patrimonio* in Spanish).

- **Holdings** carry an asset class (`crypto`, `etf`, `stock`, `fund`), a symbol, an optional label/ISIN, a native currency, and an optional link to a price provider. Each holding owns a ledger of **lots**.
- **Lots** are BUY/SELL entries (trade date, quantity, unit price, currency, optional fee and note). Net quantity, remaining cost basis and realized P&L are computed by **replaying the ledger FIFO**; the FX rate into the household base currency is frozen at trade time. Sells are validated so a position can never be oversold.
- **Pricing is API-only and never guessed.** Crypto via **CoinGecko** (with **Binance** as a keyless fallback beyond CoinGecko's history ceiling); equities via **Yahoo Finance** by default (keyless, covers European UCITS ETFs in EUR), with **EODHD** or **Twelve Data** selectable as documented alternatives; FX via **Frankfurter (ECB)**. Funds have no automatic provider and stay unpriced until entered manually in a snapshot.
- **Linking** a holding to a provider symbol happens by searching in the UI (recommended — it resolves the exact provider symbol for you), by ISIN auto-match on import for equities, or by filling the provider columns in the CSV. Unlinked or unpriced holdings show a badge and count as stale.
- **Daily price refresh** background jobs keep a shared `price_history` current (crypto intraday, FX and equities daily). Failures are isolated per symbol and self-heal on the next run; a refresh outage never crashes the app.
- **Views**: a **Portfolio** tab (totals for invested / current value / unrealized & realized P&L / total return, a per-holding table with its lots, and an asset-type filter that re-totals per class); an **Allocation** tab (pie charts by holding, by asset class, by crypto, by stock, and by latest snapshot); and an **Evolution** tab (daily market value with invested and realized/unrealized overlays plus asset-class / holding / time-range filters, shown above the snapshot-based net-worth evolution).
- **Snapshot integration**: see [Portfolio prefill](#net-worth-snapshots) above — market classes are prefilled from live valuations, stay editable, and are frozen per snapshot for audit.
- **Notifications**: buy/sell lot changes can push a Telegram message (see [Notifications](#notifications-telegram)); CSV imports stay silent.

### Loans (Préstamos)

Tracking-only records of money the household has **lent to other people**. They live in their own sub-tab under *Patrimonio → Préstamos*, alongside snapshots, movements and liabilities. They are deliberately kept out of the money path: a loan **never produces a transaction**, does not affect income/expense totals, and is **not included in net worth**. The household uses them purely to remember what it is owed and how repayment is progressing.

- **Loan terms**: borrower name (free text), principal (positive), start date, optional description (≤ 500 chars), and one of three mutually exclusive interest models:
  - **No interest** — a 0 % loan.
  - **Simple interest** — a fixed annual rate applied to the remaining principal.
  - **Compound interest** — a fixed annual rate that capitalizes monthly or yearly.
  - The annual rate is required for simple/compound; the compounding period is required only for compound. A borrower can hold several independent loans; the system never consolidates them.
- **Outstanding balance**: principal plus accrued interest from the start date (per the chosen model) up to "now", minus all payments applied so far. Interest stops accruing the moment the loan is settled or written off (frozen at its closing date).
- **Payments**: each loan has a list of received payments (date, amount, optional description). Allocation follows the standard banking order — a payment first cancels accrued interest up to its date, and only the remainder reduces principal; if it doesn't even cover the interest, no principal moves and the unpaid interest carries forward. When entering a payment the form shows, in real time, how the amount will split between interest and principal. Payments are soft-deletable and editable; any edit or deletion **recomputes the balance from scratch** over the remaining payments in chronological order.
- **Recurring repayment schedule** (optional, one per loan): frequency (weekly / monthly / yearly), the appropriate day field, an expected amount, and an active/paused flag. A daily background job materializes expected payments into the normal payment list (idempotent via a unique partial index on `loan_payments(schedule_id, payment_date)`); it shares the recurring-transactions cron entrypoint. Materialized payments are ordinary payments — interest-first, editable, deletable. Manual and scheduled payments coexist.
- **Status transitions** (owner-triggered): **settled** (saldado) when fully repaid, **written off** (incobrable) when it clearly won't be repaid. Both stop interest accrual and remove the loan from the active outstanding total, but the loan stays visible (and filterable) in the list. A written-off loan can be reopened.
- **Loans page**: opens with a header stating *"Loans you have made to others. Tracking only — not included in net worth."* (localized, disclaimer preserved). Provides a status filter (active / settled / written off / all), a "Nuevo préstamo" button, a click-through detail drawer (terms, payment history, outstanding balance, schedule, per-loan actions), and two "Export CSV" buttons (one for loans, one for payments).
- **Home panel**: a compact tile on the Dashboard, rendered only when at least one active loan exists (no empty-state placeholder). Shows the total outstanding across active loans, the active-loan count, the three largest active loans by balance, the interest-first / not-in-net-worth reminder, and a "Ver todos" link to the Loans page.

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
- Custom categories: create, rename, change essential flag, move between groups, or hard-delete with cascade (owners only). Members see the list read-only.
- Liabilities management: name + active flag (full CRUD).
- Members & invitations: issue and revoke invitations, see pending ones (owners only).
- FIRE settings (owners only).
- **Notifications** (owners only): configure the per-household Telegram integration (see below).

### Notifications (Telegram)

Each household can optionally push a Telegram message to a chosen chat whenever significant
data changes. Configured per household, owner-only, under **Settings → Notifications**.

- **Setup**: paste a bot token (from @BotFather) and a chat ID. The token is stored encrypted
  and never shown again — the form only reports whether one is configured. A **Test notification**
  button sends a fixed message and shows Telegram's immediate response inline. A collapsible help
  section walks through obtaining the token and chat ID.
- **Master toggle** pauses all notifications without losing the configuration, plus a **per-entity
  toggle** each (covering create/update/delete) for: transactions, snapshots, movements, loan
  payments, portfolio trades (buy/sell lots), recurring-transaction execution, and
  recurring-loan-schedule execution.
- **What fires**: a member's create/update/delete on those entities, and the daily scheduler
  materializing recurring transactions / loan-schedule payments (sent as one aggregated summary
  per run). **CSV imports never notify**, regardless of toggles, to avoid floods on bulk loads.
- **Message content**: a localized header (household locale), the same fields shown on that
  entity's mobile card (with category-group emoji), and an author line — the member's email, or
  "Recurring schedule (created by <owner>)" for scheduled runs. Light Telegram Markdown.
- **Delivery failures** are out of scope of the app: every dispatch attempt (and the Telegram API
  response) is written to the structured log pipeline; the app does not retry or surface failures
  in the UI. If a household sees nothing, the owner uses the Test button to verify configuration.

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
  - `sl_portfolio_prices_refreshed_total{provider}` and `sl_portfolio_price_refresh_failures_total{provider}`
  - `sl_login_attempts_total{outcome=success|failure|rate_limited}`
  - `sl_active_sessions` (gauge)
  - `sl_registrations_total{mode, outcome}`
  - `sl_invitations_issued_total{role}`, `sl_invitations_accepted_total{role}`
  - `sl_analytics_request_seconds{endpoint}` (timers for month, year, year-over-year, year-by-year, forecast, dashboard_extras, allocation, top_movers, recurring_share, heatmap, contribution_series, and FIRE projection)
- **Health probes**: `/actuator/health/liveness` and `/readiness`.
- **Telegram dispatch** is logged per attempt as structured `telegram_notify` lines (`household`, `entity`, `action`, `ok`, and the Telegram `description` on failure) — the only place delivery outcomes are observed; there is no in-app failure surfacing or retry.
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
| `TELEGRAM_TOKEN_KEY` | Base64 AES key (16/24/32 bytes) used to encrypt stored Telegram bot tokens at rest. Generate with `openssl rand -base64 32`. Required only if a household saves a bot token; keep it stable (rotating it makes stored tokens undecryptable). Optional: `TELEGRAM_API_BASE_URL`, `TELEGRAM_TIMEOUT_MS`. |
| Portfolio pricing | All optional — holdings work unpriced without them. `EQUITY_PRICE_PROVIDER` (`yahoo` default, or `eodhd` / `twelve_data`), `COINGECKO_API_KEY` (Demo key for crypto), `EODHD_API_KEY` / `TWELVEDATA_API_KEY` (only for those providers). FX (Frankfurter) and Yahoo need no key. Base-URL overrides exist for each provider; see `.env.example`. |

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
7. **Add a portfolio holding**: `/networth` → *Portfolio* → *New holding* → a crypto (e.g. BTC), link it via the search box, add a BUY lot. Prices backfill into `price_history`; confirm the holding shows a current value and P&L. Take a new snapshot and confirm the crypto class is prefilled as *computed*.
8. **Track a loan**: `/networth` → *Préstamos* → *Nuevo préstamo* → a borrower, a principal, simple interest. Open it, register a payment, and confirm the interest/principal split shows. The Dashboard should now display the outstanding-loans tile.
9. **Configure FIRE**: `/fire` → set target, contribution, scenarios. View the projection chart.
10. **Invite a partner** (owners only): `/settings` → *Members & invitations* → *Issue invitation*. Copy the link `…/register?invite=<token>` and open it in another browser session.
11. **Check observability**:
   - `docker compose logs -f backend` shows JSON logs with `requestId`, `userId`, `householdId` populated.
   - From a container on `${SHARED_NETWORK_NAME}`: `curl http://shared-ledger-backend:9090/actuator/prometheus` returns metrics, including `sl_transactions_created_total`, `sl_snapshots_created_total`, `sl_login_attempts_total`, `sl_active_sessions`, `sl_recurring_materialized_total`.
12. **Switch language**: top-right language picker → ES. Server validation messages localize on subsequent requests via `Accept-Language`.

---

## 8. Operating notes

- **Daily scheduler.** The recurring materializer runs daily at 02:00 UTC (configurable via `app.scheduler.recurring-cron`). The unique partial index on `transactions(recurring_template_id, occurrence_date)` enforces idempotency; reruns never duplicate. Failures don't crash the app — they're logged with `templateId` MDC and counted in `sl_recurring_materialization_failures_total`.
- **Portfolio price refresh.** Three background jobs keep `price_history` current: crypto intraday, FX just after midnight, and equities early morning (all cron-configurable under `app.portfolio.*`). Each is gap-filling and idempotent, isolates failures per symbol, and self-heals on the next run. An optional per-household **auto-snapshot** job can also create scheduled net-worth snapshots (off by default; daily/weekly/monthly).
- **Sessions.** 30-day sliding sessions stored in `SPRING_SESSION`. Cookie `SESSION`, `HttpOnly`, `SameSite=Lax`, `Secure` controlled by `APP_COOKIE_SECURE`. Active session count is exposed as `sl_active_sessions`.
- **CSRF.** The frontend reads the `XSRF-TOKEN` cookie and sends `X-XSRF-TOKEN` on every state-changing request. The frontend hits `/api/auth/csrf` to seed the cookie on app load and on logout.
- **Rate limiting.** Login attempts are limited per source IP via Bucket4j in-memory (5/min, 20/hour by default). State resets on restart — acceptable for a self-hosted single replica.
- **Logs.** JSON to stdout, rotated by Docker (`json-file`, 10MB × 5).
- **Backups.** Postgres is the only durable state; back up the `sharedledger` database with your usual tooling.

---

## 9. Preparing CSV files for bulk import

The Transactions, Snapshots, Movements, Portfolio, Loans and Loan-payments importers each accept a UTF-8 CSV with a fixed header row, reachable from *Settings → Data import & export*. The importer parses the file, shows a preview (rows that would be inserted, skipped as duplicates, or rejected with row-level errors), and only writes after explicit confirmation. The same screen offers a single **Export all** ZIP that bundles one CSV per dataset (transactions, recurring templates, movements, snapshots, portfolio, loans, loan payments); those files re-import cleanly.

The feeds capture different things:

- **Transactions** — day-to-day **income and expenses** (salary, rent, groceries, restaurants). This is the operational ledger used for budgets, savings rate, and the year-over-year analytics.
- **Snapshots** — periodic **net-worth recordings**: how much you currently hold in each asset class and what each liability still owes. This tracks **savings/wealth state** at a point in time.
- **Movements** — capital **moved into or out of** an asset class, or principal paid down on a liability. This tracks **what gets sent to (or taken from) savings**, separately from market gains.
- **Portfolio** — individual **investment holdings** as BUY/SELL lots (crypto, ETFs, stocks, funds). This drives per-holding valuation, P&L, and the snapshot prefill for market asset classes.
- **Loans** and **Loan payments** — money lent to other people and the repayments received against it. Tracking-only; never touches transactions or net worth.

> **Tip — converting bank statements.** Banks rarely export in the shape these importers need. The fastest path is: download the statement (CSV, OFX, PDF), open ChatGPT, Claude, or any other LLM, paste the file, and ask it to rewrite it into one of the formats below — share the column list and an example row from this section.
>
> **Always review the converted file before importing.** Open the CSV in a spreadsheet and spot-check at least the `category_code` column for transactions: LLMs frequently miscategorize merchants (gas stations bucketed as groceries, streaming charges sent to utilities, refunds flipped to expenses). Even with the importer's preview step, mis-categorized rows that pass validation will silently distort your budgets and analytics. The same review applies to `type` / target columns for movements and `kind` / `key` for snapshots.

### Transactions CSV

Header (exact column names, any order): `date,direction,category_code,amount,description,created_at,updated_at`.

| Column | Required | Values |
| --- | --- | --- |
| `date` | yes | ISO date, `YYYY-MM-DD`. Must not be more than one year in the future. |
| `direction` | yes | `income` or `expense`. Must match the category's kind. |
| `category_code` | yes | Stable category code (see list below). Custom categories use codes of the form `{group}.{slug}` (or `income.{slug}`) as created in *Settings → Custom categories*. |
| `amount` | yes | Positive decimal with up to two decimals (e.g. `1234.56`). Comma decimal separators are also accepted. |
| `description` | no | Free text, up to 500 characters. May be empty. |
| `created_at` | no | ISO-8601 instant (e.g. `2026-01-15T08:30:00Z`). Leave blank to use the current time. |
| `updated_at` | no | ISO-8601 instant. Leave blank to use the current time. |

Global `category_code` values:

- **Income** (flat): `income.salary`, `income.pension`, `income.reimbursements`, `income.benefits`, `income.financial`, `income.other`, `income.transfers`.
- **Home**: `home.rent`, `home.mortgage`, `home.utilities`, `home.insurance_fees`, `home.services`, `home.taxes`, `home.repairs`, `home.furniture`, `home.other`.
- **Transport**: `transport.fuel`, `transport.public`, `transport.airfare`, `transport.car_maintenance`, `transport.parking`, `transport.other`.
- **Groceries**: `groceries.groceries`.
- **Shopping**: `shopping.clothing`, `shopping.electronics`, `shopping.gifts`, `shopping.other`.
- **Outings**: `outings.restaurants`, `outings.travel`, `outings.subscriptions`, `outings.hobbies`, `outings.lottery`.
- **Financial**: `financial.fees`.
- **Health**: `health.medical`, `health.pharmacy`.
- **Personal**: `personal.personal_care`, `personal.education`, `personal.other`.

Rows that match an existing transaction on `(date, direction, category_code, amount, description)` are skipped automatically — re-importing the same file is safe. When the same file contains the same row more than once, every copy is imported with ` (2)`, ` (3)`, … appended to the description so each transaction stays unique, and the preview lists every renamed row before you confirm.

> **Trade-off.** Because the dedup key includes the description, the importer can't tell whether two rows that look identical are actually two separate transactions or one transaction listed twice — so once a row exists in the DB, every later upload of an identical row is skipped, including rows that appear twice in the *same* upload alongside the DB copy. To add a genuine second copy, give it a distinct description (or delete the existing row first).

Example:

```csv
date,direction,category_code,amount,description,created_at,updated_at
2026-01-31,income,income.salary,2850.00,January payroll,,
2026-02-03,expense,groceries.groceries,42.17,Mercadona,,
2026-02-05,expense,outings.restaurants,28.50,Sushi,,
```

### Snapshots CSV

Header (exact column names, any order): `date,note,kind,key,value`.

A snapshot is a group of rows sharing the same `date` (and `note`, if set). Each row carries one asset-class value or one liability balance. **Every asset class and every active liability must appear for each date** — partial snapshots are rejected.

| Column | Required | Values |
| --- | --- | --- |
| `date` | yes | ISO date. The grouping key. |
| `note` | no | Optional note for the snapshot. Must be identical across every row sharing a `date`. |
| `kind` | yes | `asset` or `liability`. |
| `key` | yes | For `asset`: one of `cash`, `fund`, `etfs`, `stocks`, `crypto`, `pension`. For `liability`: the liability's name as configured in *Settings → Liabilities* (must currently be active). |
| `value` | yes | Non-negative decimal with up to two decimals; `0` is allowed for an empty class or paid-off liability. |

When a snapshot already exists for one of the imported dates, you pick the policy on the Import dialog: `skip` (keep what's there), `replace` (delete and re-create), or `abort` (refuse the import).

Example (two snapshots, one liability called "Archena mortgage"):

```csv
date,note,kind,key,value
2026-01-31,,asset,cash,4200.00
2026-01-31,,asset,fund,18000.00
2026-01-31,,asset,etfs,9500.00
2026-01-31,,asset,stocks,2100.00
2026-01-31,,asset,crypto,300.00
2026-01-31,,asset,pension,11000.00
2026-01-31,,liability,Archena mortgage,142300.00
2026-02-28,Bonus paid in,asset,cash,5100.00
2026-02-28,Bonus paid in,asset,fund,18750.00
2026-02-28,Bonus paid in,asset,etfs,9600.00
2026-02-28,Bonus paid in,asset,stocks,2050.00
2026-02-28,Bonus paid in,asset,crypto,280.00
2026-02-28,Bonus paid in,asset,pension,11200.00
2026-02-28,Bonus paid in,liability,Archena mortgage,141950.00
```

### Movements CSV

Header (exact column names, any order): `date,type,asset_class_code,liability_name,amount,description,created_at`.

| Column | Required | Values |
| --- | --- | --- |
| `date` | yes | ISO date. Must not be more than one year in the future. |
| `type` | yes | `contribution`, `withdrawal`, or `debt_payment`. |
| `asset_class_code` | conditional | Required when `type` is `contribution` or `withdrawal`; must be empty otherwise. One of `cash`, `fund`, `etfs`, `stocks`, `crypto`, `pension`. |
| `liability_name` | conditional | Required when `type` is `debt_payment`; must be empty otherwise. Must match a liability configured for the household. |
| `amount` | yes | Positive decimal with up to two decimals. |
| `description` | no | Free text, up to 500 characters. |
| `created_at` | no | ISO-8601 instant. Leave blank to use the current time. |

Rows that match an existing movement on `(date, type, target, amount, description)` are skipped automatically. When the same file contains the same row more than once, every copy is imported with ` (2)`, ` (3)`, … appended to the description so each movement stays unique, and the preview lists every renamed row before you confirm.

> **Trade-off.** Same caveat as transactions: the dedup key includes the description, so once a row exists in the DB, every later upload of an identical row is skipped. To add a genuine second copy, give it a distinct description (or delete the existing row first).

Example:

```csv
date,type,asset_class_code,liability_name,amount,description,created_at
2026-01-05,contribution,etfs,,500.00,DCA — VWCE,
2026-01-15,contribution,fund,,300.00,DCA — world index,
2026-02-01,debt_payment,,Archena mortgage,650.00,February principal,
2026-03-10,withdrawal,stocks,,1200.00,Sold AAPL,
```

### Portfolio CSV

Uses the **European-CSV dialect**: UTF-8, **semicolon (`;`) column separator**, **comma (`,`) decimal**, ISO dates. One row per lot; rows sharing `(asset_class, symbol)` group into one holding, created on demand. This is exactly what *Portfolio → Export CSV* produces.

Header: `type;asset_class;symbol;label;native_currency;isin;provider;provider_symbol;traded_on;quantity;unit_price;cost_currency;fee;note`.

| Column | Required | Values |
| --- | --- | --- |
| `type` | no | `BUY` or `SELL`. Empty defaults to `BUY`. |
| `asset_class` | yes | `crypto`, `etf`, `stock`, or `fund`. |
| `symbol` | yes | Your identifier for the holding (e.g. `BTC`, `WEBN`, `AAPL`); uppercased. |
| `label` | no | Display name; taken from the first row of the holding. |
| `native_currency` | no | ISO 4217 currency the instrument trades in. Defaults to EUR. |
| `isin` | no | 12-char ISIN; used to auto-link stocks/ETFs on import when no provider is given. |
| `provider` | no | `coingecko`, `yahoo`, `eodhd`, or `twelvedata`. Optional way to link the price source directly. |
| `provider_symbol` | conditional | The holding's exact identifier at that provider (e.g. `bitcoin`, `WEBN.DE`). Required whenever `provider` is set; both must be given together or both left empty. |
| `traded_on` | yes | Trade date, `YYYY-MM-DD`. |
| `quantity` | yes | Positive, comma decimal, up to 12 decimals. |
| `unit_price` | yes | Price per unit in `cost_currency` (purchase price for BUY, sale price for SELL). |
| `cost_currency` | no | Currency of `unit_price`/`fee`. Defaults to `native_currency`. |
| `fee` | no | Optional trade fee in `cost_currency`. |
| `note` | no | Free text (≤ 500 chars). Not part of the duplicate check. |

Provider columns are the round-trip mechanism for keeping links across export/import. For everyday use it's easiest to leave them blank and link each holding from the app's search after importing — that resolves the exact provider symbol for you. Sells must be covered by earlier buys (an oversell is a row error at preview). Rows matching an existing lot on `(type, asset_class, symbol, traded_on, quantity, unit_price, currency)` are skipped, so re-importing an export is safe.

Example:

```csv
type;asset_class;symbol;label;native_currency;isin;provider;provider_symbol;traded_on;quantity;unit_price;cost_currency;fee;note
BUY;crypto;BTC;Bitcoin;EUR;;coingecko;bitcoin;2025-11-15;0,05;62000,00;EUR;5,00;DCA
BUY;etf;WEBN;Amundi Prime All Country;EUR;IE0009OA6R05;yahoo;WEBN.DE;2026-01-10;120;9,87;EUR;1,50;
SELL;etf;WEBN;Amundi Prime All Country;EUR;IE0009OA6R05;yahoo;WEBN.DE;2026-05-10;50;11,05;EUR;1,50;Rebalancing
BUY;fund;MSCIW;World index fund;EUR;;;;2026-01-31;15,5;95,20;EUR;;
```

### Loans CSV

Loans and their payments use the **European-CSV dialect**: UTF-8, **semicolon (`;`) column separator**, **comma (`,`) decimal**, RFC-4180 quoting, ISO dates, positive amounts only. The first row is the literal header. This is exactly what *Préstamos → Export loans CSV* produces.

Header: `borrower_name;principal_amount;start_date;interest_type;annual_interest_rate;compounding_period;description;status`.

| Column | Required | Values |
| --- | --- | --- |
| `borrower_name` | yes | Free text (≤ 120 chars). The person you lent to. |
| `principal_amount` | yes | Positive decimal, comma decimal (e.g. `1000,00`). |
| `start_date` | yes | ISO date, `YYYY-MM-DD`. Date the loan was made. |
| `interest_type` | yes | `none`, `simple`, or `compound` (lowercase). |
| `annual_interest_rate` | conditional | Percentage points (e.g. `5,00` = 5 %). Required when `interest_type` is `simple` or `compound`; must be empty for `none`. |
| `compounding_period` | conditional | `monthly` or `yearly`. Required when `interest_type=compound`; must be empty otherwise. |
| `description` | no | Free text up to 500 characters. |
| `status` | no | `active`, `settled`, or `written_off`. Defaults to `active`. |

Rows whose `(borrower_name, start_date, principal_amount)` match an existing loan are skipped automatically.

Example:

```csv
borrower_name;principal_amount;start_date;interest_type;annual_interest_rate;compounding_period;description;status
Alice;1000,00;2025-01-01;none;;;Loan for car repair;active
Bob;5000,00;2024-06-15;simple;5,00;;;active
Carol;3000,00;2024-03-01;compound;4,50;monthly;Renovation loan;active
```

### Loan payments CSV

Header: `borrower_name;loan_start_date;payment_date;amount;description`.

| Column | Required | Values |
| --- | --- | --- |
| `borrower_name` | yes | Must match an existing loan's `borrower_name`. |
| `loan_start_date` | yes | Must match that loan's `start_date`; together with the borrower it locates the loan. |
| `payment_date` | yes | ISO date of the payment received. |
| `amount` | yes | Positive decimal, comma decimal. |
| `description` | no | Free text. |

If a row matches no loan it is rejected (`IMPORT_LOAN_UNKNOWN`); if the `(borrower_name, loan_start_date)` pair matches more than one loan the row is rejected as ambiguous (`IMPORT_LOAN_AMBIGUOUS`). Rows whose `(borrower_name, loan_start_date, payment_date, amount, description)` match an existing payment are skipped automatically.

Example:

```csv
borrower_name;loan_start_date;payment_date;amount;description
Alice;2025-01-01;2025-02-01;200,00;First repayment
Bob;2024-06-15;2024-07-15;500,00;
```

---

## 10. Local development (no Docker)

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

## 11. Project layout

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

## License

Copyright © 2026 Sephilabs.

This project is licensed under the **GNU Affero General Public License v3.0** (AGPL-3.0-only). See [LICENSE](LICENSE) for the full text.

The AGPL extends the GPL with a network-use clause: if you run a modified version of this software as a network service, you must make the complete corresponding source code of your modified version available to its users. Use, study, and modification are otherwise free under the terms of the license.
