# shared-ledger

Self-hosted personal-finance application for a household of one or two. Replaces a spreadsheet workflow for monthly transaction tracking and periodic net-worth snapshots, with budgets, recurring templates, an investment portfolio with automatic pricing, lending tracking, cash management with a flow-based estimate, analytics, and a FIRE projection on top.

Single repository, two services orchestrated with Docker Compose:

- **backend** — Kotlin / Spring Boot 4 on JDK 25, Spring Security with cookie sessions, Spring Data JPA + Flyway, Spring Session JDBC, Micrometer + Prometheus, JSON logs.
- **frontend** — React 19 + TypeScript on Vite 8, TanStack Query for server state, react-i18next for localization, Tailwind 4 with in-house primitives, Recharts 3 for charts, axios for HTTP. Served behind nginx in production with `/api` proxied to the backend.

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
- Roles within a household: **owner** and **member**. Only owners may modify household settings, manage invitations, change roles, or delete the household. Two deliberate exceptions live under *Settings* but are open to every member: **linking a bank** (each member links their own account with their own SCA — sharing bank credentials to work around the role would defeat the point) and **categorisation rules** (shared logic over the movements members already confirm). Both are described under *Bank ingestion*.
- Every household-scoped API endpoint verifies the caller is a member of the household. Household IDs in paths are never trusted without that check.
- **Switching households**: when a user belongs to more than one household, a dropdown appears in the header to switch the active household. With a single household the dropdown is hidden.
- **Default household**: each user can pick one of their memberships as their default. On login (and on any new device), the app selects the default first; if none is set, the first membership is used. The default is stored per user and survives a clean browser.
- **Changing a member's role**: owners can promote a member to `owner` or demote an owner back to `member` from *Settings → Members & invitations*. The household must always keep **at least one owner** — demoting the last one is refused with `LAST_HOUSEHOLD_OWNER` (and the button is disabled), since this action is itself owner-only and there would be no way back. An owner may hand over by promoting someone else first, then demoting themselves.
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

Periodic recordings of the value of each asset class, each named asset, and the outstanding balance of each liability. Snapshots are the source of truth for current net worth.

- Each snapshot has a date and an optional note. **At most one snapshot per date** (enforced by a unique database index); creating a second one on the same date is rejected with `SNAPSHOT_DATE_EXISTS` — edit the existing snapshot instead. Multiple per month are still fine; monthly views use the latest one in the month.
- The **Assets** block has two subgroups shown under one heading: **Classes** — the fixed fungible classes `Cash`, `Funds`, `ETFs`, `Stocks`, `Crypto`, `Pension` (one value per class) — and **Named assets** (see [Assets](#assets-named)) auto-filled from each asset's dated series. **Cash** is prefilled from its own [flow-based estimate](#cash-adjustment-series--flow-based-estimate) (marked *computed*); correcting it re-anchors the cash series (a new adjustment at the snapshot date).
- **Liabilities** are named entities (see [Liabilities & amortizable loans](#liabilities-and-amortizable-loans)). A **simple** liability auto-fills its last known balance from its own series; an **amortizable** loan auto-fills the **computed balance from its schedule at the snapshot date** (marked *computed*, never 0). Every active liability appears in the form.
- **Net worth = Assets (classes + named) − Liabilities.** Names (not UUIDs) are shown for named assets and liabilities, including for entities later soft-deleted.
- The snapshot creation form **prefills from the previous snapshot** and shows delta (absolute and percent) next to each input. If any delta exceeds 50% the form requires explicit confirmation before saving — a typo guard.
- Named assets and liabilities auto-fill from a per-date "named values" endpoint and are marked *computed*, exactly like market classes; editing a value overrides it. Values are frozen into the snapshot on save; past snapshots are never rewritten when an asset/liability is later edited.
- **Evolution view**: stacked area chart of assets by class over time, with a net worth line (assets minus liabilities) overlaid. When movements exist, a dashed cumulative-contributions line is overlaid as well; the gap between it and the net-worth line is the revaluation (gain or loss), surfaced in the tooltip in both absolute terms and as a percent of contributions.
- **Allocation view**: pie chart by asset class at the latest snapshot.
- **Portfolio prefill**: market asset classes (crypto, ETFs, stocks, funds) are prefilled from the [Portfolio](#portfolio-investments) at the snapshot date and marked *computed*; editing a value marks it *overridden*, and a class with no priced holdings is *carried over* from the previous snapshot. A per-holding valuation is frozen into each snapshot for audit. Snapshots remain the source of truth — the prefill only removes the manual tallying.
- Queries support an optional date range.

### Net worth movements

A separate, dedicated workflow for recording capital movements (contributions to investments, withdrawals from investments, principal payments on debt). Kept deliberately apart from the day-to-day transactions log because these are reallocations of capital, not operational income or expenses.

- Each movement has: date, type (`contribution` / `withdrawal` / `debt_payment`), target (an asset class for contributions and withdrawals, a liability for debt payments), amount (positive), optional description, audit fields.
- A contribution to an asset class represents new capital flowing into that class (e.g. a DCA purchase of ETFs).
- A withdrawal represents capital leaving a class (e.g. selling stocks).
- A debt principal payment reduces the outstanding principal of a specific liability.
- Movements live under the **Flows** container tab (alongside Cash) in the Wealth hub, distinct from transactions.
- **Movements do NOT alter snapshot values.** Snapshots remain the source of truth for current value. Movements enrich the analytical picture by separating capital flow from market return.
- A SQL CHECK constraint enforces the type/target combination at the database level.

What movements unlock:

- Cumulative contributions per asset class over any period.
- Real return per asset class = current snapshot value − net contributions since inception (or since any chosen start date).
- Investment cash-flow view by month or year.
- Principal repayment history per liability.
- On the FIRE page: the real **money-weighted (XIRR) annualized return**, the movements-derived contribution mode, and the gain-fraction estimate behind the capital-gains tax model — none of which exist without movements.

If the operator chooses not to record movements, the app remains correct (net worth via snapshots still works); they only lose the contribution-vs-return decomposition.

### Cash (adjustment series + flow-based estimate)

Cash is a **hybrid**: a manual adjustment series is the source of truth, and between adjustments the balance is **estimated** from the flows the household marks as affecting cash. It lives in a **Cash** sub-tab under the **Flows** container in the Wealth hub (next to Movements). Cash stays an aggregate asset *class*, not a named asset — there is a single cash balance, not multiple accounts (v1).

- **Adjustment series (the truth).** A dated series of `date + amount` entries ("at the close of day X I had Y"), editable and deletable exactly like an asset's value history. An adjustment is the truth of cash at that date; from there cash is estimated via flows.
- **Estimate between adjustments (computed on demand, never cached).** Estimated cash at a date = the latest adjustment on/before that date **+ the net of the marked flows dated strictly after that adjustment**. Only adjustments (the truth) and each snapshot's frozen value are persisted; the intermediate estimate is always derived.
- **End-of-day convention.** An adjustment dated `D` means cash at the **close of day D**, so the estimate counts only flows dated **strictly after D** — flows on `D` itself are considered already included. Dates stay day-precision; between multiple adjustments on the same day, the **last one created wins** (tie-break by creation order).
- **Signed flows (bidirectional).** Each flow adds or subtracts by its sign: **Transactions** (income **+**, expense **−**), **Lent** (lending out **−**, repayment received **+**), **Movements** (withdrawal **+**, contribution **−**, debt payment **−**). The amortizable-loan instalment is **not** a cash flow — the real payment is a transaction (which does affect cash) while the schedule only reduces the liability, so there's no double counting.
- **Per-type toggles (per household).** An on/off toggle for each of Transactions / Lent / Movements, configured in the Cash sub-tab itself (not general Settings), all on by default. A user who captures a flow type incompletely can turn it off and lean on manual adjustments.
- **Two-panel layout.** Left: the current estimated balance and the adjustment series (add/edit/delete), with inline helper text making the end-of-day meaning explicit. Right: the flow toggles and a breakdown of the estimate ("since your last adjustment on X (€Y), net flows +€Z → estimated €W"). Panels stack on mobile.
- **Snapshot integration.** Creating a snapshot prefills Cash with the estimate (marked *computed*, like market classes). Editing that value **re-anchors** — it writes a new adjustment at the snapshot date and freezes the value into the snapshot. Both the Cash sub-tab and the snapshot write to the **same** series. Past snapshots are never rewritten when adjustments or later flows are added.
- **Compatibility.** With no adjustments and no marked flows, cash behaves exactly as before — carried over from the previous snapshot. The first anchor is created from the Cash sub-tab (or by correcting a snapshot once a series exists).

### Portfolio (investments)

Individual market holdings — crypto, ETFs, individual stocks, and funds — tracked at the lot level with automatic pricing. Portfolio sits alongside snapshots, the Flows container (movements + cash), assets, liabilities and lendings as sibling tabs under the **Wealth** hub (*Patrimonio* in Spanish).

- **Holdings** carry an asset class (`crypto`, `etf`, `stock`, `fund`), a symbol, an optional label/ISIN, a native currency, and an optional link to a price provider. Each holding owns a ledger of **lots**.
- **Lots** are BUY/SELL entries (trade date, quantity, unit price, currency, optional fee and note). Net quantity, remaining cost basis and realized P&L are computed by **replaying the ledger FIFO**; the FX rate into the household base currency is frozen at trade time. Sells are validated so a position can never be oversold.
- **Pricing is API-only and never guessed.** Crypto via **CoinGecko** (with **Binance** as a keyless fallback beyond CoinGecko's history ceiling); equities via **Yahoo Finance** by default (keyless, covers European UCITS ETFs in EUR), with **EODHD** or **Twelve Data** selectable as documented alternatives; FX via **Frankfurter (ECB)**. Funds have no automatic provider and stay unpriced until entered manually in a snapshot.
- **Linking** a holding to a provider symbol happens by searching in the UI (recommended — it resolves the exact provider symbol for you), by ISIN auto-match on import for equities, or by filling the provider columns in the CSV. Unlinked or unpriced holdings show a badge and count as stale.
- **Daily price refresh** background jobs keep a shared `price_history` current (crypto intraday, FX and equities daily). Failures are isolated per symbol and self-heal on the next run; a refresh outage never crashes the app. When some holdings read stale or unpriced, the Portfolio tab's stale-price notice offers a **Refresh prices** action that triggers the same gap-fill on demand (off-thread, with a short cooldown so it can't hammer providers); the table updates as the new prices land.
- **Price freshness per holding**, deliberately quiet in the collapsed table. A holding whose price is past its class tolerance carries a small **amber dot** next to the price (the date it came from on hover); a fresh one shows nothing at all, so the table keeps its density. **Expanding** a holding always states it in words on the provider line — *"Linked to coingecko · bitcoin · EUR · price from Jul 29, 2026, 5:46 AM (2h ago)"* — the one place the exact clock time of an intraday observation appears. Unpriced funds read *"manual — valued via snapshots"* there instead. Localized in both languages.
- **What "old" means follows the refresh cadence of the asset class**, so the age and the highlighting always speak about the same thing: **crypto** is dated from the observation instant `price_history` stores (its price date sits at today all day and would say nothing), while **ETFs and stocks** are dated in calendar days from their trading day (the instant would read "8h ago" for a Friday close fetched the night after it — true about the fetch, misleading about the price). The tolerance is **3 hours** for crypto (an hourly cadence has missed a run) and **4 calendar days** for equities (deliberately generous: a Friday close is still the right price on Monday, and on Tuesday after a holiday, and there is no market calendar to consult). Closed positions are never dated — their value is zero at any price — and neither are funds, whose value only moves when it is entered by hand.
- **Oldest price: X ago** appears inside the stale-price notice, and only while something actually is stale — the worst case over open, priced holdings, in one glance. Since the per-class tolerances are tighter than the backend's global `stale-price-threshold-days`, a price can trip them while the global notice stays silent; that case shows the same line on its own rather than hiding the signal.
- **Refresh cadence, explained in place**: an ⓘ next to the Portfolio subtitle gives two lines — crypto roughly hourly, stocks/ETFs and FX once a day after market close, funds only via snapshots — and points at the expanded row for a specific price's date. The copy stays deliberately approximate, since every cron is operator-configurable.
- **Views**: a **Portfolio** tab (totals for invested / current value / unrealized & realized P&L / total return plus a money-weighted return (XIRR) card, a per-holding table with its lots, and an asset-type filter that re-totals per class); an **Allocation** tab (pie charts by holding, by asset class, by crypto, by stock, and by latest snapshot); and an **Evolution** tab (daily market value with invested and realized/unrealized overlays, a **ROI (TWR)** chart — time-weighted return that chains daily market returns between trades, so contributions and withdrawals don't count as performance — plus asset-class / holding / time-range filters, shown above the snapshot-based net-worth evolution).
- **Benchmark overlays** (Evolution → ROI chart, off by default): opt in to overlay market references — **S&P 500**, **MSCI World**, **Gold**, **Bitcoin** (the set is data-driven from a `benchmark` table, so more can be added with a seed row + a label, no chart changes). Each is normalized the same way as your TWR curve (0% at the period start, on the same % axis and date window), and only ever compared against **TWR** — never the money-weighted (XIRR) figure, where an index comparison would be meaningless. Benchmarks quote in USD but are shown in **EUR terms**, converted at each day's ECB rate so the comparison never silently embeds EUR/USD drift; the chart states this. Multi-select shows several at once, each a distinct line. The overlay always respects the current asset-class / holding / range filters and re-bases to 0% at the start of that window. Daily closes are stored in a dedicated `benchmark_price` table (independent of `price_history`) and read from storage, not fetched live at render time; where a benchmark lacks data for part of the window the gap is shown, not faked.
- **P&L percentages** (Portfolio tab totals and the Home Portfolio tile): the euro amounts are fixed, but each user chooses the base the percentages are shown over, in **Settings → Portfolio return percentages** — a per-account (not per-household) visual preference that changes only the denominators. Three bases:
  - **Return on invested capital** (default): unrealized, realized and total return all over the FIFO cost basis of open lots, so they share one base and add up like the euros.
  - **Return on net invested (own money)**: all three over net invested — the money contributed from outside the portfolio (open-lots cost − realized P&L, computed straight from those summary values so buys funded by prior sales cancel out). This is "your own money currently at risk"; also one shared, additive base. When net invested is ≤ 0 (you've withdrawn at least as much as you put in — "house money") the percentages show "—" rather than divide by a zero/negative base.
  - **Return on capital turned over** (the original behavior): realized % over the FIFO cost basis of every sold lot across the whole history, total return % over the total capital deployed (open + sold cost basis), unrealized % over open cost — a return-on-cost figure where money that is sold and reinvested deliberately counts in both terms, so heavy sell-and-rebuy trading (common with crypto) inflates the base and makes the percentages look small.

  No basis is time-weighted or money-weighted (the TWR chart and the XIRR card cover those). A percentage shows "—" whenever its denominator is zero or unavailable (e.g. realized % before any sale, or house money under net invested), never a fake 0%, and hovering a percentage shows the exact division behind it.
- **Net invested ("Own money")**: under the Invested figure — on both the Portfolio page card and the Home tile — a muted secondary line shows net invested (open-lots cost − realized P&L, the same value the net-invested percentage base uses) with an ⓘ that explains it: the money that actually left your pocket, with buys funded by prior sales cancelling out, and the subtraction instantiated. It is always shown regardless of the selected percentage basis (a fact, not a preference); when it is ≤ 0 the ⓘ explains "house money" — current positions funded entirely by realized gains. The main Invested number never changes.
- **Money-weighted return (XIRR)** (Portfolio tab): the annual growth rate of the money actually deployed, accounting for when it was deployed — the timing-aware complement to return-on-cost, and unrelated to the FIRE page's snapshot-based return figure. Computed live from the lot ledger: each buy is an outflow of its total cost (fees included) and each sell an inflow of its net proceeds, both in EUR at the lot's **frozen trade-time FX rate** (historical flows are never re-converted with today's rates), with the current market value of open holdings as a terminal inflow today. The rate is found by bisection with an expanding bracket, so it never diverges. The card label states the basis explicitly: **(XIRR, annualized)**, or **(XIRR, cumulative)** when the history spans less than one year — those show the cumulative money-weighted return instead, because annualizing short periods produces absurd numbers. The card shows "—" with a one-line reason when there are no trades, when an open holding is unpriced (the terminal value would be incomplete), or when the flows admit no rate (e.g. no money has come back and the position is worthless). The asset-type filter swaps in the same metric computed per class, server-side over that class's own lots and current value (XIRR cannot be derived from subtotals) — an unpriced holding blanks only its own class and the whole-portfolio figure. Its "How is this calculated?" disclosure spells out the flow count, date range and terminal value used.
- **Snapshot integration**: see [Portfolio prefill](#net-worth-snapshots) above — market classes are prefilled from live valuations, stay editable, and are frozen per snapshot for audit.
- **Notifications**: buy/sell lot changes can push a Telegram message (see [Notifications](#notifications-telegram)); CSV imports stay silent.

### Assets (named)

Named things the household owns with a value of their own (property, vehicles, valuables) — distinct from the fungible asset *classes*. They live in an **Assets** tab under the Wealth hub and **add** to net worth.

- Each asset has a name, a type (`property` / `vehicle` / `other`) and an active flag, plus its **own dated value series** (the source of truth): add a value at a date any time; the latest entry is the current value.
- Mark an asset **inactive** (e.g. sold) to drop it from new snapshots; history is kept.
- One editor at a time: item details (name/type/active) and the value-history list are separate, and the summary card always shows the current value + date (never "no value yet" once entries exist).
- Snapshots read each active asset's value at the snapshot date (auto-filled, editable, frozen). Assets and simple liabilities share one layout — an asset **adds**, a liability **subtracts**.
- Full CSV export/import cycle (see [Assets CSV](#assets-csv)).

### Liabilities and amortizable loans

Named debts that **subtract** from net worth, in a **Liabilities** tab under the Wealth hub. A liability is either **simple** or an **amortizable loan**.

- **Simple liability**: name + active flag + its **own dated balance series** (like an asset, but subtracts). Same single-editor card: details vs balance-history list.
- **Amortizable loan** (mark a liability *amortizable* and set a **charge day**, 1–31; required): the balance is **computed by a schedule**, not typed.
  - **Multi-part**: a loan is one or more **parts** (e.g. a Florius mortgage with two parts under a unified instalment); the loan's total balance and instalment are the **sum of the parts**.
  - **Method per part**: French (default, constant instalment), German (constant principal, decreasing instalment), interest-only (bullet), zero-interest.
  - **Flexible part inputs**: principal + rate + **one of** {term in months, end date, instalment}; the other two are computed and shown read-only (term ↔ end date convert; entering the instalment computes the term).
  - **Start mode per part**: *from current balance* (outstanding + balance date → project forward, no past) or *from origin* (original principal + start date → full schedule including past payment history).
  - **Rate revisions** (dated, past dates allowed) recompute the schedule from each.
  - **Re-anchor** (origin mode): set the real outstanding balance at a date and the schedule reprojects from there — a safety valve for drift; earlier history is kept.
  - **Odd-days first period**: a mid-cycle start prorates the whole first instalment (interest and principal) by the fraction of the cycle the loan actually existed.
  - **Prepayments** are a separate action (record / simulate), not part of the definition: **reduce-term** (keep the instalment, finish earlier) or **reduce-instalment** (keep the term, lower the instalment — recomputed per method); *simulate* shows interest saved, new term and new instalment without saving.
  - **Payment history**: a read-only, month-by-month table split into **past (elapsed)** and **upcoming (projection)**, plus a **metrics** summary (principal/interest paid to date, outstanding, interest remaining, total interest over life, progress %). Corrections go only through prepayment / rate revision / re-anchor — rows are never hand-edited.
  - **Monthly job**: a background job advances each amortizable loan on its charge day, persisting the charged instalments (interest / principal / resulting balance) as generated history — **autonomous from transactions** (neither reads nor creates any), idempotent per (part, charge date), sharing the daily recurring-materializer cron.
- Snapshots and the tab show the **name**, never the UUID.
- Full CSV export/import cycle (see [Liabilities CSV](#liabilities-csv) and [Amortization CSV](#amortization-csv)).

### Lending (Prestado)

Tracking-only records of money the household has **lent to other people**. They live in their own sub-tab under *Patrimonio → Prestado*, alongside snapshots, movements and liabilities. They are deliberately kept out of the money path: a lending **never produces a transaction**, does not affect income/expense totals, and is **not included in net worth**. The household uses them purely to remember what it is owed and how repayment is progressing.

- **Lending terms**: borrower name (free text), principal (positive), start date, optional description (≤ 500 chars), and one of three mutually exclusive interest models:
  - **No interest** — a 0 % lending.
  - **Simple interest** — a fixed annual rate applied to the remaining principal.
  - **Compound interest** — a fixed annual rate that capitalizes monthly or yearly.
  - The annual rate is required for simple/compound; the compounding period is required only for compound. A borrower can hold several independent lendings; the system never consolidates them.
- **Outstanding balance**: principal plus accrued interest from the start date (per the chosen model) up to "now", minus all payments applied so far. Interest stops accruing the moment the lending is settled or written off (frozen at its closing date).
- **Payments**: each lending has a list of received payments (date, amount, optional description). Allocation follows the standard banking order — a payment first cancels accrued interest up to its date, and only the remainder reduces principal; if it doesn't even cover the interest, no principal moves and the unpaid interest carries forward. When entering a payment the form shows, in real time, how the amount will split between interest and principal. Payments are soft-deletable and editable; any edit or deletion **recomputes the balance from scratch** over the remaining payments in chronological order.
- **Recurring repayment schedule** (optional, one per lending): frequency (weekly / monthly / yearly), the appropriate day field, an expected amount, and an active/paused flag. A daily background job materializes expected payments into the normal payment list (idempotent via a unique partial index on `lending_payments(schedule_id, payment_date)`); it shares the recurring-transactions cron entrypoint. Materialized payments are ordinary payments — interest-first, editable, deletable. Manual and scheduled payments coexist.
- **Status transitions** (owner-triggered): **settled** (saldado) when fully repaid, **written off** (incobrable) when it clearly won't be repaid. Both stop interest accrual and remove the lending from the active outstanding total, but the lending stays visible (and filterable) in the list. A written-off lending can be reopened.
- **Lending page**: opens with a header stating *"Money you lend to others — tracking only. Not counted toward your Wealth (a deliberate choice) and not linked to transactions."* (localized, disclaimer preserved). Provides a status filter (active / settled / written off / all), a "Prestar dinero" button, a click-through detail drawer (terms, payment history, outstanding balance, schedule, per-lending actions), and two "Export CSV" buttons (one for lendings, one for payments).
- **Home panel**: a compact tile on the Dashboard, rendered only when at least one active lending exists (no empty-state placeholder). Shows the total outstanding across active lendings, the active-lending count, the three largest active lendings by balance, the interest-first / not-in-net-worth reminder, and a "Ver todos" link to the Lending page.

### Analytics

Beyond raw lists, the app provides comparisons and projections.

The Dashboard (landing page) shows the current month and current year at a glance and adds a hero row of up to six at-a-glance tiles: the two described below plus **Current wealth**, **Portfolio**, **Money lent** and **Pending bank movements**. Those four are data-dependent — they render only when they have something to show (no empty-state placeholder). Independently of that, each user can hide any of the six tiles from *Settings → Home panels* (see Settings); the monthly/yearly summaries and expenses charts are always shown. The app logo in the header is a link back to this page from anywhere in the app (keyboard-focusable, Enter activates it; clicking it while already on Home does not add a history entry). On the login screen the logo is not a link, since Home is behind authentication.

- **Trailing 12-month savings rate**: primary number is the trailing-12-month rate; below it, the year-to-date and current-month rates. Beside the primary number, a small sparkline plots the trailing-12 rate month by month over the last 24 months so the trend is visible without opening Analytics.
- **Fixed cost of living**: trailing-12-month average of recurring (template-backed) expenses, expressed as €/day (÷30.44) and €/year (×12). A secondary line shows the same metrics including discretionary expenses for comparison. If fewer than 12 months of data exist, the average is taken over the months available and that count is shown.

The **Analytics** section is organized into six tabs — **Daily**, **Explorer**, **Composition**, **Changes**, **Trends** and **Money flow**. Composition, Changes and Trends group related sections on one scrollable page; old per-section URLs redirect to the right tab and anchor.

**Range selector.** Daily's pattern charts, Explorer (both panels), Cost of living and the Heatmap share one range control, with the same options everywhere: **3 months, 6 months, year to date, 1 year, 2 years, all time, or a custom from–to pair**. The same control drives Portfolio and Net-worth *Evolution*. Two granularities:

- **Month-bucketed views** (Explorer, Cost of living, Heatmap) snap a custom range outwards to whole months — a from of 14 March becomes 1 March, a to of 9 February becomes 28 February — and print the snapped span underneath (*"Whole months: Mar 2025 – Feb 2026 · 12 months"*) so the adjustment is never silent.
- **Daily's pattern charts** apply a custom range at day precision, since they bucket by weekday and day-of-month.

**Date format.** Every date the app writes out as text uses one order in every language — **day, named month, year**: `31 Jul 2026 – 2 Aug 2026` in English, `31 jul 2026 – 2 ago 2026` in Spanish. No per-locale reordering, so a range never has to be re-read against a different convention, and the named month is what makes day-first safe where a numeric `31/07` vs `07/31` would not be. The month-level snap hint follows the same order minus the day (`Sep 2025`). Native date *inputs* still show whatever numeric format the browser picks — that's the OS picker, not ours; the app-formatted text is the authoritative one. One formatter (`formatDayMonthYear` in `lib/dates.ts`) backs all of it, and month names come from that same source, so a month reads identically in a range label, a snap hint, a chart axis and a heatmap column.

Each view remembers its own range for the rest of the browser session, so moving between tabs and back doesn't reset it. A custom range is only queried once both ends are set and correctly ordered; the endpoints reject a reversed pair or a span wider than 20 years. Where a range reaches further back than the household's history, every view uses the data that exists and states how much that was — nothing is padded or extrapolated.

- **Daily** (expenses only): a month calendar where each day is shaded by how much was spent, with a month summary (total, average per spending day, highest day) and prev/next/today navigation. Clicking a day opens a drill-down panel: the day's total, its per-category breakdown, the individual transactions, and how the day compares against the period's daily average and against the average for that same weekday (both labelled with the active range). Below the calendar, pattern charts show average spend by day-of-week and by day-of-month over the selected range.
- **Explorer** (expenses only): a per-category deep dive. Pick one expense category or a whole group to get stat tiles (average and median per month, highest month, lowest non-zero month) and a monthly bar chart with an optional prior-year overlay — always **the same span shifted back one year**, including for year-to-date and custom ranges (absolute and percent deltas in the tooltip); for a single category, a top-descriptions table lists where the money actually went within the range (occurrences, total, average per occurrence). A second, stacked panel charts any set of groups/categories as stacked monthly bars with an optional total line, for side-by-side composition over time. The two panels keep independent ranges.
- **Composition** — how spending is made up:
  - **Allocation**: donut chart of how income for the selected month or calendar year was split across expense groups (Home, Transport, Groceries, Shopping, Outings, Financial, Health, Personal), plus a "Saved" slice for the positive remainder. A ranked table below repeats the figures for exact reading.
  - **Cost of living**: essential vs non-essential monthly averages over the selected range (with the essential share as a percentage), plus per-category breakdown tables for both sides; each row clicks through to the transactions list pre-filtered by that category. The average is per month across the months of the range, and the caption names it — *"Average over Mar 2025 – Feb 2026 · 12 months of data"*. When the household's history is shorter than the range, the divisor is the months that actually have data and the caption's count says so.
  - **Recurring vs discretionary**: share of expenses generated by an active recurring template versus the rest, for a selected scope (current/past month, trailing 12, year-to-date, or any calendar year).
- **Changes** — what moved:
  - **Top movers**: top-5 increases and top-5 decreases in category spending for the selected month against a chosen baseline — same month last year, or trailing 6-month average. Categories absent from the baseline but with spending in the period surface separately as "New activity".
  - **Heatmap**: a category-by-month matrix where each cell's color intensity reflects spending. Colour is scaled per row so categories with very different magnitudes (e.g. Mortgage vs Books) are both readable. Range uses the shared selector; a toggle swaps the matrix to income categories; clicking a cell drills into the transactions list pre-filtered by that category and month.
- **Trends** — the long view:
  - **Year-over-year same-month comparison**: pick a month; compare totals and per-category breakdown across the last N years for which data exists.
  - **Year-by-year comparison**: pick years; show monthly income / expenses / savings as one line per year over a 12-month X axis.
  - **Trailing 12 months**: income, expenses and net savings per month over the last 12 months, plus the savings-rate line.
  - **Forecast**: per category, project the next 1–12 months from historical data. Method: simple moving average over a configurable window (3 / 6 / 12 months). For categories backed by an active recurring template, the template amount is used instead of the historical average (more accurate). Historical context is shown alongside the projection so it's interpretable.
- **Money flow** (*Flujo del dinero* — deliberately not "Cash flow", which would collide with Wealth → Flows → Cash): a Sankey diagram of where money came from and where it went in a selected period. Income categories on the left flow into a single "Money flow" hub; the hub flows out to expense **category groups** by default (a toggle switches to leaf categories), plus a **"Saved"** node for the positive remainder — the same `max(income − expenses, 0)` rule as Allocation's Saved slice. When expenses exceed income, a **"Deficit"** node on the left feeds the hub with the shortfall instead (a Sankey cannot draw negative flows; the imbalance is never dropped or clamped). Nodes below **2 %** of their side's total collapse into a per-side **"Others"** node whose tooltip lists the collapsed members; tooltips show the absolute amount and the share of that side's total. Scope selector: selected month, trailing 12 months, year to date, any calendar year, or a custom from/to range. The backend endpoint reuses the exact aggregation behind Allocation, so for the same period the per-group figures of both tabs match to the cent. Node colors are stable per group/category and shared with Allocation. An empty period shows a friendly message instead of an empty chart; on phones the diagram scrolls horizontally inside its card rather than shrinking below readability.

### FIRE projection

Long-term wealth projection toward financial independence, derived from the household's real data (spending, savings, movements, snapshots) with manual overrides always available. The framework is explicitly **nominal**: return scenarios are nominal and targets inflate year by year at a configurable expected inflation (default 2 %/year).

**Targets — three tiers derived from real spending, plus an optional manual one:**

- **Lean FIRE** = essential trailing-12 spending ÷ SWR — the same essential base as the Cost-of-living tile.
- **FIRE** = total trailing-12 spending ÷ SWR.
- **Fat FIRE** = total spending × a configurable multiplier (default 1.5) ÷ SWR.
- **Custom target** — an optional fourth tier with a user-set nominal amount (the pre-v2 single target migrated here; it neither inflates nor grosses up).
- The **safe withdrawal rate** is configurable per household (default 4 % — the classic ×25). Each spending base (essential, total) has a persisted source mode: **derived** (default — recomputed live on every query, never frozen; with fewer than 12 months of data the available months are used and the count is shown) or **manual** (a user-entered monthly amount, letting tiers work even before any transactions exist). The derived value always stays visible next to an override so the gap with real spending is never hidden, and one single effective value per base feeds targets, tax gross-up, coverage, the table and every explanation. If manual overrides make essential exceed total, a non-blocking warning notes that Lean FIRE would exceed FIRE — values are never reordered or clamped. Each tier can be toggled on/off for the chart and table.

**Projection engine, per scenario** (each a **(mean %, std-dev %)** pair, e.g. 6 % ± 15 %):

- A **deterministic year-by-year path** using the scenario mean, starting **from the latest snapshot's qualifying-class total** — the live Portfolio value plays no part in the projection.
- A **Monte Carlo simulation** (default 10 000 trials, configurable via `FIRE_MONTE_CARLO_TRIALS`): Gaussian annual returns clamped to ±3σ, with per-year **percentile bands (p10 / p25 / p50 / p75 / p90)**.
- The hit condition uses each year's **inflated** target; the chart draws one rising target curve per active tier (dashed) next to the scenario bands.
- The **monthly contribution** has three source modes — **manual**, **derived from savings** (trailing-12 average of income − expenses) and **derived from movements** (trailing-12 net contributions into qualifying classes). All three values are always displayed so the save-vs-invest gap stays visible; a derived mode without data warns loudly instead of silently projecting 0. A toggle (default on) indexes the contribution to inflation.
- **Scenario × tier summary table**: deterministic hit year, P(hit) within the window and median hit year — plus the **Coast FIRE** counterparts (the same simulation with contributions stopped today).
- **Current coverage** per active tier: qualifying wealth in the latest snapshot ÷ target, as a percentage with a progress bar.

**Real annualized return — money-weighted (XIRR):** computed over the recorded net-worth movements between the first and latest snapshots, restricted to the qualifying classes, so contributions count as capital, not performance. **If no movements exist in the range, no return figure is shown at all** — the UI prompts to record movements instead (ROI or nothing). The figure is only trustworthy if the recorded movements cover the contributions made since the first snapshot: when the **first recorded movement lags the first snapshot by more than a month**, a **non-blocking caveat** notes that the uncovered earlier months may overstate the return (the number is still shown, and the projection is never altered) and the "How is this calculated?" states the gap. Once a valid return exists, an **"Add scenario from my history"** button creates a scenario from the mean/std-dev of the per-year money-weighted returns, permanently labeled *"(historical — reference)"* — a deliberately thin sample that never becomes a default.

**Capital-gains tax (Spanish seed, editable):** an optional toggle (default on) converts each spending tier from net to gross — targets then cover the tax paid when selling to live. Brackets are **data, not code**: a per-household editable table seeded with the Spanish savings-base scale (Ley 7/2024, FY 2025–2026: 19/21/23/27/30 %). The net→gross conversion is closed-form per bracket; the taxable share of a withdrawal (**gain fraction**) is derived **per Monte Carlo path** from its own cost basis, and from recorded movements for today's deterministic metrics (with a manual fallback while movements are insufficient). Loss offsetting is deliberately ignored — a conservative simplification — and the UI states this is a planning estimate, not tax advice.

**Transparency:** every derived figure carries a *"How is this calculated?"* explanation instantiated with the household's own numbers ("Lean FIRE = annual withdrawal of €X ÷ 3.5 % SWR = €Y"), each setting shows a one-line repercussion with real numbers, and an **assumptions block** next to the chart (SWR, inflation, indexation, contribution source, spending base, tax treatment, data as-of date) makes the projection auditable at a glance. All FIRE parameters live on the FIRE page itself (owner-only saves) and persist per household; derived values are never persisted.

### Internationalization

- English and Spanish supported throughout the UI.
- Server-side messages (validation errors, auth errors, generic API errors) are also localized.
- Locale resolution order: `Accept-Language` header → authenticated user's preferred locale → English.
- Currency, date and number formatting reflect the active locale and the household currency.
- **Third-party UI terms are never translated.** Where instructions send you to an external console —
  Enable Banking's Control Panel, whose pages are English-only — the prose is localized but every string
  you have to *find on screen* (tab, button, option, field label: “API applications”, “Add a new
  application”, “Register”, “Restricted Production”, …) stays verbatim in English and quoted, so it reads
  as *look for exactly this*. Our own labels are the opposite: always the translated ones.
- Language toggle in the header persists per user.
- **Theme**: light / dark / OLED / system toggle in the header menu. `system` follows the OS preference and updates live, resolving to light or dark only — never OLED. OLED is a pure-black variant for OLED phone screens (black pixels switch off, so it saves battery and maximizes contrast); like light and dark, choosing it explicitly fixes the theme and stops it following the OS. Stored per browser, not per user.

### Settings

- Change language (per user).
- Change password.
- **Home panels** (per user): show/hide each of the six top Dashboard tiles — Savings rate, Cost of living, Current wealth, Portfolio, Money lent, Pending bank movements. Stored server-side per user, so it survives new devices and never affects other members of the household. The toggle can only *hide* a tile: data-dependent tiles (marked with an info hint in Settings) still appear only once they have data, even when switched on. The month/year summaries and the expenses chart are always shown and not listed here.
- **Households**: list every household the user belongs to, mark one as default, switch the active household, create a new household, or hard-delete an owned household (subject to the default-household restriction described above).
- Household name, currency and default locale (owners only).
- Custom categories: create, rename, change essential flag, move between groups, or hard-delete with cascade (owners only). Members see the list read-only.
- **Auto-snapshot** (owners only): enable scheduled net-worth snapshot creation and pick the frequency — daily, weekly or monthly. Off by default; a daily background job creates the snapshot when one is due.
- Members & invitations: every member sees the member list (role, joined date); owners issue and revoke invitations, see pending ones, and change any member's role (see *Households*).
- FIRE settings are edited on the FIRE page itself (saving is owner-only); assets, liabilities and their amortization schedules are managed in the Wealth hub tabs, not in Settings.
- **Banks** (any member): link your own bank, and manage the connections you linked. See *Bank ingestion* for who can manage what.
- **Notifications** (owners only): configure the per-household Telegram integration (see below).
- **Danger zone — Delete all data** (owners only): a confirmation-gated action (type `delete`) that permanently wipes *every* piece of the household's financial data while keeping the household itself and its membership/invitations. It hard-deletes (including soft-deleted rows) transactions, budgets, recurring templates, net-worth snapshots and movements, cash adjustments, assets, liabilities and lendings (with their value/balance/amortization/schedule/payment history), the investment portfolio (holdings, lots and valuations), bank connections (with their accounts, sync runs, pending movements and categorization rules), and the FIRE (including its tax-bracket table), Telegram, auto-snapshot and cash-estimate settings. Global reference data (categories, asset classes, FX rates, price history) is never touched.

### Notifications (Telegram)

Each household can optionally push a Telegram message to a chosen chat whenever significant
data changes. Configured per household, owner-only, under **Settings → Notifications**.

- **Setup**: paste a bot token (from @BotFather) and a chat ID. The token is stored encrypted
  and never shown again — the form only reports whether one is configured. A **Test notification**
  button sends a fixed message and shows Telegram's immediate response inline. A collapsible help
  section walks through obtaining the token and chat ID.
- **Master toggle** pauses all notifications without losing the configuration, plus a **per-entity
  toggle** each (covering create/update/delete) for: transactions, snapshots, movements, lending
  payments, portfolio trades (buy/sell lots), recurring-transaction execution, and
  recurring-lending-schedule execution.
- **What fires**: a member's create/update/delete on those entities, and the daily scheduler
  materializing recurring transactions / lending-schedule payments (sent as one aggregated summary
  per run). Amortizable-liability events reuse the existing toggles (no new subsystem): the **monthly
  instalment run** rides the recurring-lending toggle, and a **recorded prepayment** (with the new
  balance) rides the lending-payments toggle. **CSV imports never notify**, regardless of toggles, to
  avoid floods on bulk loads.
- **Message content**: a localized header (household locale), the same fields shown on that
  entity's mobile card (with category-group emoji), and an author line — the member's email, or
  "Recurring schedule (created by <owner>)" for scheduled runs. Light Telegram Markdown.
- **Delivery failures** are out of scope of the app: every dispatch attempt (and the Telegram API
  response) is written to the structured log pipeline; the app does not retry or surface failures
  in the UI. If a household sees nothing, the owner uses the Test button to verify configuration.

Re-link reminders and batch-confirm summaries for bank ingestion (below) flow through this same
system, gated by two extra per-household toggles (bank movements, bank connections).

### Bank ingestion (open banking / PSD2)

Connect real bank accounts through **Enable Banking** (a licensed PSD2 aggregator, read-only
account information — AIS) and sync movements into a **review inbox**. Nothing enters the ledger
automatically: confirming a pending movement is what generates a normal transaction (or, for
capital reallocations, a net-worth movement — see the review inbox below). This is a
connector abstraction (Enable Banking is the primary provider; Yapily is a documented alternative;
Wise could be added via its own API) with the always-available CSV import as the fallback.

- **Credentials are per household, configured in the app** (Settings → Banks → *Enable Banking
  credentials*, owner-only). Each household creates its own API application in the
  [Enable Banking Control Panel](https://enablebanking.com), activates **Restricted Production**
  (real data, free, only for linked accounts — no eIDAS/TPP licence needed), and pastes the
  application id + RSA private key into the card. The key is validated on save (PKCS#8; a PKCS#1
  paste is rejected with the conversion command), stored AES-GCM encrypted with
  `ENABLE_BANKING_SECRET_KEY`, and **never shown again** — same write-only contract as the Telegram
  bot token. A *Validate credentials* button makes a harmless authenticated call so a wrong
  application id or mismatched key surfaces at save time, not at the first sync. There is **no
  instance-wide fallback**: a household without credentials sees only that card, and the rest of the
  feature (link form, pending inbox, nav badge, Home card) stays hidden.
  - The card also shows the **redirect URL to register** in that application, with a copy button.
    That URL is *instance* configuration, not per household — `ENABLE_BANKING_REDIRECT_URL` (§5) —
    so every household registers the same value in its own EB application. It must match exactly or
    every link is rejected. If the variable is unset the app derives the URL from the request
    (`Origin` / `Referer`, falling back to the proxied `Host`), which keeps local development
    working; set it explicitly in production so the value can't shift with how the app is reached.
  - **Sync identity.** Every connection records the application id it was authorized under, because
    the bank ties its consent to that application. Sync uses only the credentials saved here: enter
    the same application id and key your connections were created with and they keep working; a
    different application marks them *credentials changed — re-link needed* rather than silently
    failing or re-routing them. Saving credentials with a different application id warns first
    ("N existing connection(s) … will need re-linking") and needs a confirmation.
  - **Setup instructions in three ordered phases** (the card's collapsible *How do I get these?*), because
    the order is what people get wrong: **A** — create the EB application (with the exact navigation path:
    account menu → *API applications*, plus a direct link to `enablebanking.com/cp/applications`), register
    the redirect URL, fill
    the fields the form calls optional but rejects when empty (description, data-protection email, and
    privacy/terms URLs — the last two offered as copy buttons on this instance's public base URL, taken from
    the same source as the redirect URL), then save the credentials here and press *Validate credentials*
    (gate: don't continue until validation succeeds). Pressing *Register* is spelled out as the two things
    that happen at once, because both are missed in practice: the private key file **downloads by itself**
    (only copy, never shown again) and the application id is **the UUID in parentheses after the application
    name in the API Applications list** (shown as a `name(uuid)` example), **not inside that file**; **B** — whitelist every account in the Control Panel, with the *why* stated first, the
    per-account walkthrough ("Activate by linking accounts", the holder passes their own SCA, repeat for
    each bank and each holder), and a visually prominent closing gate: *don't link any bank in SharedLedger
    until every account appears as linked to the application*; **C** — only now link the banks in the Banks
    section, plus the symptom→fix line for a connection that shows `active` with 0 accounts. The
    Restricted-Production warning below sits inside phase B, where the action happens.
  > **Restricted Production caveat — whitelist each account.** In this mode Enable Banking only
  > returns accounts you have explicitly linked to the *application* in the Control Panel
  > ("Activate by linking accounts"). A holder can pass SCA at *any* bank, but if that specific
  > account isn't whitelisted, the API silently strips it and returns an **empty account list**: the
  > connection links and shows `active`, yet every sync fetches zero movements with no error
  > (`bank_sync_accounts … accounts=0`). Symptom seen in practice: Wise synced but ING didn't,
  > because only Wise had been linked to the application. **Fix:** link each account you intend to
  > use (per bank) in the Control Panel, then re-link the connection in the app. This whitelist step
  > is Control-Panel-only — there is **no API** to do it from the app. Applying for **full
  > production** removes the restriction so any authorised account is returned automatically.
- **Linking (from Settings → Banks) is open to every member, not just owners**: the app first
  explains what will happen (redirect to the bank for SCA, only movements are read, the permission
  expires in 90–180 days and needs re-linking, only linked accounts are accessible, data stays
  self-hosted). The bank picker is the provider's ASPSP catalogue filtered by country (not a fixed
  list). Choosing a bank redirects the account holder to authenticate; on return the account is
  associated with the household.
  - **First-link checkpoint.** Because the whitelist mistake is invisible afterwards, the household's
    *first* link (no connections yet — once per **household**, not per member) shows a small interstitial
    before the redirect: "Have you whitelisted this account in your Enable Banking application? Non-
    whitelisted accounts link fine but sync nothing", with *Yes, continue* and *Review instructions* (jumps
    to phase B of the credentials card; shown to owners, the only ones who see that card). Dismissible, and
    never shown for later links or re-links.
- **Who can manage a connection.** Every member sees every connection in the household (and the
  movements it brings in — bank data is household data). *Managing* one — sync now, re-link, rename,
  disable ingestion, delete — belongs to the member who linked it, plus any owner; for anyone else
  those actions are hidden in the UI and refused by the API (`NOT_CONNECTION_MANAGER`). Connections
  created before per-member linking have no recorded linker and stay owner-only. The SCA callback
  must be finished by the same member who started it.
- **Two moments**: the account holder passes SCA at their own bank (a person, never the server);
  the app then manages the ongoing consent session and prompts for re-link before it expires.
  Relatives each link their own account with their own SCA — credentials are never shared.
- **Multiple connections** per household, including several to the same bank (e.g. two Wise
  accounts), each with its own label, holder, consent, sync cursor, status, and call budget.
- **Sync** follows Enable Banking's fetch rules and comes in two shapes:
  - **Initial (on link)** — a **full-history** sync using `strategy=longest` (the provider finds the
    earliest available transaction and pulls everything forward). It runs asynchronously right after
    linking but **interactively** (the holder's PSU headers are sent), so it is *not* a background
    fetch and is *not* gated by the per-day budget — it pages through all history.
  - **Background (scheduled, 1–2×/day, never live)** — an **incremental** sync using
    `strategy=default` from the **last sync point minus a small overlap** (a few days, to catch
    late-booked items) up to **today**. Both window bounds are **inclusive** and the upper one is
    never a future date, and the lower one is **clamped to the ~90-day unattended history window**:
    strict banks (Bankinter) reject an out-of-range or future window outright with a bare
    `ASPSP_ERROR` instead of trimming it. Unattended fetches are limited by the bank to **~4 per
    consent per day**; on `ASPSP_RATE_LIMIT_EXCEEDED` the connection **backs off ~6h** and retries,
    surfacing the status. "Sync now" from the UI is interactive (PSU) and incremental — some banks
    only serve transactions while the holder is online, so it is also the workaround when background
    fetches keep failing.
  - **Pagination**: every fetch pages via `continuation_key`; the **only** stop condition is a
    response with **no** continuation key — an empty (or short) page may still carry one, so paging
    continues until it's absent. Page size varies by bank. If the bank breaks **mid-pagination**, the
    pages already delivered are still stored (dedup makes it idempotent) — a bank that dies on page 2
    still yields page 1 instead of nothing — while the run is recorded as failed so it is retried.
  - **Dedup / idempotency**: movements are keyed by **(connection + bank movement id)**, where the id
    is `entry_reference` when present, else `transaction_id`, else a stable composite — because
    `entry_reference` can be missing or duplicated. The overlap re-read therefore never duplicates.
  - Each connection syncs independently — one expired, backing-off, or failing connection never blocks
    the others — and every run is audited (success/error + code + new-movement count).
  - **Credential states are parked, not failed.** Before anything else, each run resolves the
    household's credentials: none configured → the connection shows *credentials required*; a
    different application id than it was linked under → *credentials changed — re-link needed*.
    Either way the run stops there with no sync-run record, no notification and no error metric, so
    background sync stays quiet. Both states are re-checked every run and clear themselves as soon
    as the credentials line up again — no manual nudge needed.
- **Review inbox** (a **Pending (N)** sub-view inside Transactions, plus a nav badge and a Home
  card, all at household level): per-movement **confirm / reject** and **batch** actions.
  Each row is edited inline before confirming — direction, category (confirm stays disabled until
  one is chosen) and description, the last as a plain text input prefilled with the bank's own
  wording (counterparty – description, exactly what a confirm would store on its own) — and the
  bulk bar can assign a category to the whole selection at once. All three edits are **local until
  the row is resolved**: they are saved when you press Confirm (or complete a Replace or Split that
  inherits them), reject discards them, and navigating away discards them. Nothing is written per
  keystroke. Batch confirm uses whatever each row's inputs hold at that moment. Confirming reuses
  the normal transaction-creation logic; single confirms notify normally, batch confirms send one
  aggregated notification. A **possible-duplicate warning**
  flags manual transactions that look like an incoming movement (warns only, never auto-discards);
  those rows get a third action, **Replace**, described below. Replace and **Split** are alternative
  resolutions for a flagged row — both stay available on it.
  Rejected items don't reappear in the pending list; they stay visible under a "rejected" filter,
  from which they can be **restored** back to pending (individually or in batch). Confirmed items
  are final — they already produced a transaction or movement. The list also filters by
  connection, free-text search, categorisation state (suggested / unsuggested), and
  duplicates-only, can be grouped by connection or category, and all filters are clearable in one
  click.
- **Mark as movement** (per item, under "More"): instead of a transaction, a pending item can be
  confirmed as a **net-worth movement** — for capital reallocations (a transfer to savings, a debt
  principal payment) that shouldn't land in the income/expense ledger. A small dialog collects the
  movement **type** (preselected from the item's direction — income → withdrawal, expense →
  contribution, switchable to debt payment) and **target** (asset class, or liability for debt
  payments); the item's date and amount carry over. It reuses `MovementService` (same target
  validation as the manual Movements tab), records the produced movement via `created_movement_id`,
  and marks the item `confirmed` like any other — no category or learned rule is involved. This is a
  single-item action; batch confirm stays transaction-only.
- **Replace a possible duplicate** (per item, only on rows carrying the duplicate flag): the third
  outcome for a movement you already entered by hand. Confirming it would leave two rows in the
  ledger and rejecting it would throw the bank data away, so Replace instead **reconciles** the two.
  A dialog compares the **existing transaction** and the **incoming movement** side by side (date,
  amount, direction, category, description, source; stacked on phones), highlighting exactly what
  would change. Confirming **updates the existing transaction in place** — no delete-and-recreate: it
  takes the movement's **date, amount and description**, and becomes the item's
  `created_transaction_id`, the same linkage a normal confirm produces, so the pair is never flagged
  again. The **category is kept** (the human-chosen one is usually the right one) but can be re-picked
  in the dialog, with the same category/direction consistency check as every other write path; the
  description is prefilled with the bank's and can be reverted to the manual one in one click. Because
  it *is* a transaction update, it reuses `TransactionService.update` — so `updated_by` is the
  confirming member, the per-entity Telegram notification fires as an **update** (not a creation), and
  any **recurring-template link is preserved**, never silently detached or reattached. The item is
  marked `confirmed` with the usual audit fields and leaves the pending list; cancelling writes
  nothing. Scope guards: it only ever targets a transaction that actually matches the movement (same
  direction and amount within the duplicate window), a **candidate must be picked explicitly when more
  than one matches**, a transaction that already resolves another movement can't be replaced (it's
  shown with the reason instead), and if nothing replaceable is left — the match was deleted or
  already linked — the dialog says so and offers a normal Confirm. Single-item only; it is excluded
  from batch operations, like "Mark as movement".
- **Split a movement** (per item, under "More"): when one bank charge covers several categories — a
  €120 supermarket bill that was €90 groceries and €30 household goods — it can be divided into **two
  or more transactions** instead of landing as one. The dialog starts with two parts, each with an
  **amount, a percentage, a category and a description**; parts can be added and removed down to a
  minimum of two (one part is just an ordinary Confirm). Category and description are prefilled from
  the row (the category it currently has selected, the description as edited in the inbox) and are
  editable **per part**. **Direction is not** — a single bank movement cannot be part income and part
  expense, so every part inherits the row's direction, and the category picker is filtered to it.
  - **Amount and percentage are two views of one value.** Editing either updates the other; the euro
    amount is authoritative and every percentage shown is derived from `amount / total`, so percent
    input never compounds its own rounding (a part stored as €3.33 of €10.00 reads back as 33.30 %).
  - **With exactly two parts the other one auto-balances** to the remainder, so the pair always
    matches to the cent. With **three or more** there is no non-arbitrary part to absorb a change, so
    nothing auto-balances: a live remainder line reports "€X left to assign" / "€X over the total"
    and Save stays disabled until the sum matches exactly. A cent left over by percentage entry
    (33.33 % three times over €10.00 is €9.99) surfaces there rather than being silently absorbed.
  - **Validation before saving**: every part needs an amount greater than 0 with at most two decimals
    and a category (the same rule as Confirm), and the parts must sum to the movement's total exactly.
    The error is reported on the part that is actually wrong — including the two-part case where
    entering more than the total leaves the other part with nothing.
  - Saving creates **one transaction per part**, all on the movement's booking date, and marks the item
    `confirmed` and associated with **every** created transaction. Cancelling writes nothing.
  - **No rule is learned from a split**: one counterparty mapping to several categories is exactly the
    ambiguity a learned rule cannot represent.
  - Telegram gets **one aggregated notification** ("1 movement split into N transactions" with the
    movement total), not one creation message per part.
  - Single-item only; excluded from batch operations, like "Mark as movement".
  - **Data model**: a `pending_movement_transactions` join table records every transaction a pending
    item produced — for splits and for ordinary confirms alike — so "does this transaction already
    resolve a bank movement?" (the Replace guard, and the "already linked" flag on a replace candidate)
    has one place to look and covers split-created transactions too. `created_transaction_id` keeps its
    original meaning, the single transaction an ordinary confirm or a Replace produced, and is left null
    for a split, where no single transaction represents the item.
- **Categorisation**: configurable rules (counterparty / description / amount → category +
  direction) applied during sync, and learning from corrections — confirming with a category
  remembers "this counterparty → this category" for next time, but **only when no existing rule
  (manual or learned) already matches the movement**: a confirmation that differs from a matching
  rule's suggestion is treated as a one-off exception, not a new rule. No ML. An **Apply rules**
  action re-runs the rules over the uncategorized pending items on demand (useful after adding
  rules). Rules are managed on their **own page** (Settings → Categorisation rules) **by any member**
  — they are shared household categorisation logic, at the same level as the transactions and pending
  movements they act on — with free-text search over the matched value, combinable filters (match
  field, direction, category, manual/learned origin), sorting (creation date, value, category),
  pagination, in-place editing, and multi-select bulk delete — handy for pruning piled-up learned rules.
- **Multi-currency** (e.g. Wise): non-EUR movements are converted to EUR at the ECB rate of the
  booking date, keeping the original amount/currency on the pending item.
- **Read-only**: only account/movement information is ever requested; there is no payment
  initiation and no way to move money.

The bank-ingestion Settings section, the Pending sub-view, the badge, and the Home card follow a
three-level visibility rule: no credentials configured → nothing appears; credentials but no bank
linked → Settings appears (to link) but the inbox/badge/card do not; at least one bank linked →
everything appears.

---

## 2. Non-functional behavior

### Security

- Cookie-based session authentication: `HttpOnly`, `Secure` (toggleable for non-HTTPS local dev via `APP_COOKIE_SECURE`), `SameSite=Lax`.
- CSRF protection enforced on every state-changing request. The frontend seeds the `XSRF-TOKEN` cookie via `GET /api/auth/csrf` on app load and forwards it as `X-XSRF-TOKEN` automatically.
- All state-changing endpoints require authentication; all household-scoped reads enforce membership.
- Passwords hashed with **Argon2id** via Spring Security's `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`.
- Login attempts rate-limited per source IP via Bucket4j (in-memory).
- CSV/data imports rate-limited per user (10/hour by default, configurable) across all import endpoints.
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
  - `sl_benchmark_prices_refreshed_total{benchmark}` and `sl_benchmark_price_refresh_failures_total{benchmark}`
  - `sl_bank_movements_ingested_total` and `sl_bank_sync_failures_total`
  - `sl_login_attempts_total{outcome=success|failure|rate_limited}`
  - `sl_active_sessions` (gauge)
  - `sl_registrations_total{mode, outcome}`
  - `sl_invitations_issued_total{role}`, `sl_invitations_accepted_total{role}`
  - `sl_analytics_request_seconds{endpoint}` (timers for month, year, year-over-year, year-by-year, forecast, dashboard_extras, allocation, money_flow, top_movers, recurring_share, heatmap, daily, cost_of_living, explorer, contribution_series, portfolio_benchmarks, and FIRE projection)
- **Health probes**: `/actuator/health/liveness` and `/readiness`.
- **Telegram dispatch** is logged per attempt as structured `telegram_notify` lines (`household`, `entity`, `action`, `ok`, and the Telegram `description` on failure) — the only place delivery outcomes are observed; there is no in-app failure surfacing or retry.
- Grafana/Prometheus/Loki stack is **not** part of this repo. The app exposes the data; the operator wires up their own monitoring against the endpoints.

### Operations

- A background scheduler runs daily; failures are isolated per template, logged with `templateId` MDC, and counted in failure metrics. The app does not crash.
- All container logs use the `json-file` driver with rotation (10MB × 5).

#### Scheduled jobs and when they run

All scheduled jobs fire in `app.scheduler.timezone` (env `SCHEDULER_TIMEZONE`), which **defaults to UTC**. The table shows the default UTC time and the equivalent local time for a Central-European operator (CET = UTC+1 in winter, CEST = UTC+2 in summer):

| Job | UTC (default) | CET (winter) | CEST (summer) |
| --- | --- | --- | --- |
| FX rate refresh | 00:30 | 01:30 | 02:30 |
| Equity price refresh | 01:00 | 02:00 | 03:00 |
| Benchmark series refresh | 01:15 | 02:15 | 03:15 |
| Recurring / lending / amortization materialize | 02:00 | 03:00 | 04:00 |
| Auto-snapshot due-check | 06:00 | 07:00 | 08:00 |
| Bank sync (incremental) | 07:00 & 19:00 | 08:00 & 20:00 | 09:00 & 21:00 |
| Consent-expiry reminder | 08:00 | 09:00 | 10:00 |
| Crypto price refresh | hourly at :05 | hourly at :05 | hourly at :05 |

The ordering matters: **FX refresh runs before equity refresh** so non-EUR equity valuations convert with the day's rate. **Leave `SCHEDULER_TIMEZONE` at UTC.** Business dates are stamped in UTC (`LocalDate.now()`), so pointing the scheduler at a non-UTC zone would run the early-morning jobs on the *previous* UTC calendar day and break that FX→equity ordering. If you want the jobs to run at a different wall-clock time, shift the cron expressions rather than the timezone.

---

## 3. Prerequisites

- Docker Engine with Compose v2 (`docker compose ...`, no `version:` key).
- A running Postgres reachable on a shared Docker network.
- The shared Docker network already exists (`docker network create infra_shared`, or whatever name you choose).

For local development outside Docker:

- JDK 25 (Eclipse Temurin recommended) and Gradle 9.6.1+
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
| Bank ingestion | All optional. The application id and private key are **not** env vars — each household pastes its own in Settings → Banks. What stays here: `ENABLE_BANKING_SECRET_KEY` (base64 AES key, `openssl rand -base64 32`; encrypts the stored credentials and bank sessions at rest — keep it stable, rotating it makes them undecryptable) and `ENABLE_BANKING_REDIRECT_URL` (your public frontend URL + `/settings/banks/callback`; it identifies the instance, so every household registers the same value in its own EB application — left blank the app derives it from the request, fine for local dev). `ENABLE_BANKING_APP_ID` is read **once at startup**, on the first boot after `V030`, to stamp connections created before credentials were per household (see §Upgrading below); drop it afterwards — a fresh install never needs it. Optional overrides: `ENABLE_BANKING_BASE_URL`, `ENABLE_BANKING_CONSENT_VALID_DAYS`, `ENABLE_BANKING_BACKFILL_DAYS`, `ENABLE_BANKING_TIMEOUT_MS`, `ENABLE_BANKING_MAX_CALLS_PER_DAY` (keep at 4 in production — the PSD2 unattended-access cap). |
| `FIRE_MONTE_CARLO_TRIALS` | Trials per scenario for the FIRE Monte Carlo simulation. Default `10000`. |

The `.env` file is git-ignored. `docker compose` reads it implicitly and the backend reads variables from it (via `env_file`).

---

## 6. Start the application

```bash
docker compose up -d --build
```

The backend image builds through BuildKit cache mounts holding Gradle's dependency cache and its
incremental-compile state, so a rebuild after a code change recompiles only what changed instead of the
whole module. The first build on a machine has nothing to reuse and takes the full time.

That state surviving between builds is the whole point, and also the thing to distrust when a build
behaves oddly. To rebuild from nothing — empty caches, no reused layers:

```bash
docker compose build --no-cache backend
```

The `backend-image` CI job builds the same way on a runner that has no caches at all, so the
from-scratch path is exercised on every push. `docker builder prune` also empties the mounts, along with
everything else.

What happens on first boot:

1. Flyway applies all migrations against the configured database.
2. The Spring Session JDBC schema (`SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`) is created alongside the application's own tables.
3. The bootstrap runner sees an empty `users` table and creates the admin user + initial household from `ADMIN_*` and `BOOTSTRAP_HOUSEHOLD_*`.
4. Backend exposes the application on the internal network at `backend:8080`, and Prometheus metrics at `backend:9090/actuator/prometheus` — the management port is **not** published to the host.
5. Frontend nginx serves the SPA and reverse-proxies `/api` to the backend.

Open `http://<host>:${FRONTEND_PORT}` (defaults to port `8087`) and log in with `ADMIN_EMAIL` / `ADMIN_PASSWORD`.

### Putting Nginx Proxy Manager (or any reverse proxy) in front

Both services attach to the single shared external network (`${SHARED_NETWORK_NAME}`), and the frontend publishes its HTTP port on the host. Two ways to point NPM at it — pick whichever fits your setup:

**Path A — over the Docker network (recommended if NPM is in a container on this host).** Attach NPM to the same `${SHARED_NETWORK_NAME}` network. Then in NPM's Proxy Host config, set the **Forward Hostname / IP** to `shared-ledger-frontend` and **Forward Port** to `80`. No host port needs to be reachable from outside; you can set `FRONTEND_BIND_ADDR=127.0.0.1` in `.env` to keep the host port loopback-only as defense in depth.

**Path B — over the host's published port (use this if NPM lives on the host or on a different machine).** Leave `FRONTEND_BIND_ADDR=0.0.0.0` in `.env` so the published port answers on all host interfaces. In NPM set **Forward Hostname / IP** to the host's IP (or `host.docker.internal` if NPM runs in Docker on the same host) and **Forward Port** to `${FRONTEND_PORT}`. Lock the port down at the OS firewall so only NPM (and your own admin IPs) can reach it.

In both paths, NPM terminates TLS and forwards `X-Forwarded-For`, `X-Forwarded-Proto`, and `Host`. The backend trusts these headers via Tomcat's `RemoteIpValve` (`server.forward-headers-strategy=native`), so the session cookie's `Secure` flag and the rate limiter's per-IP keying work correctly behind the proxy. Keep `APP_COOKIE_SECURE=true` in `.env` for external access.

`RemoteIpValve` trusts only **private-range** proxies by default (10/8, 172.16/12, 192.168/16, 127/8) and derives the real client IP from the right of the `X-Forwarded-For` chain — so a client can't spoof `X-Forwarded-For` to dodge the login rate limiter. If your reverse proxy sits on a **public** IP (e.g. NPM on a separate host reached over the internet), add that IP to the trusted set with `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` (a regex), or the limiter will key every request on the proxy's single IP.

---

## 7. First-time verification recipe

After step 6:

1. **Log in** as the bootstrap admin. The dashboard renders empty.
2. **Add a transaction**: `/transactions` → *Quick add* → expense, today's date, an amount, a category. Confirm it appears in the list.
3. **Create a recurring template**: `/recurring` → *New template* → monthly rent on day 1. Hit *Materialize now*. Confirm the corresponding transactions for the months that already passed appear in `/transactions`. Click *Materialize now* again — the count must not change (idempotency).
4. **Set a budget**: `/budgets` → edit the "Groceries" row for the current month, save, then verify the *Month summary* table shows it.
5. **Take a net-worth snapshot**: `/networth` → *Snapshots* → *New snapshot* → values for every asset class, save. View *Evolution* and *Allocation*.
6. **Record a net-worth movement**: `/networth` → *Flows* → *Movements* → *Create* → contribution to ETFs. Then open *Flows* → *Cash*, add an adjustment, and confirm the estimated balance reflects flows dated after it.
7. **Add a portfolio holding**: `/networth` → *Portfolio* → *New holding* → a crypto (e.g. BTC), link it via the search box, add a BUY lot. Prices backfill into `price_history`; confirm the holding shows a current value and P&L. Take a new snapshot and confirm the crypto class is prefilled as *computed*.
8. **Track a lending**: `/networth` → *Prestado* → *Prestar dinero* → a borrower, a principal, simple interest. Open it, register a payment, and confirm the interest/principal split shows. The Dashboard should now display the money-lent tile.
9. **Configure FIRE**: `/fire` → with the transactions, snapshots and movement from the previous steps, the Lean/FIRE/Fat tiers, coverage and projection derive automatically; tweak SWR, inflation, contribution source, scenarios or the tax brackets in the on-page settings and confirm targets move coherently across chart, tiers and table.
10. **Invite a partner** (owners only): `/settings` → *Members & invitations* → *Issue invitation*. Copy the link `…/register?invite=<token>` and open it in another browser session.
11. **Check observability**:
   - `docker compose logs -f backend` shows JSON logs with `requestId`, `userId`, `householdId` populated.
   - From a container on `${SHARED_NETWORK_NAME}`: `curl http://shared-ledger-backend:9090/actuator/prometheus` returns metrics, including `sl_transactions_created_total`, `sl_snapshots_created_total`, `sl_login_attempts_total`, `sl_active_sessions`, `sl_recurring_materialized_total`.
12. **Switch language**: top-right language picker → ES. Server validation messages localize on subsequent requests via `Accept-Language`.

---

## 8. Operating notes

- **Daily scheduler.** The recurring materializer runs daily at 02:00 UTC (configurable via `app.scheduler.recurring-cron`). The unique partial index on `transactions(recurring_template_id, occurrence_date)` enforces idempotency; reruns never duplicate. Failures don't crash the app — they're logged with `templateId` MDC and counted in `sl_recurring_materialization_failures_total`.
- **Portfolio price refresh.** Background jobs keep `price_history` current: crypto intraday, FX just after midnight, and equities early morning; a fourth keeps the `benchmark_price` series (indices/commodities/crypto) current just after equities and tops up its own FX (all cron-configurable under `app.portfolio.*`). Each is gap-filling and idempotent, isolates failures per symbol/benchmark, and self-heals on the next run; the benchmark series also bootstraps its full history on first start. An optional per-household **auto-snapshot** job can also create scheduled net-worth snapshots (off by default; daily/weekly/monthly).
- **Sessions.** 30-day sliding sessions stored in `SPRING_SESSION`. Cookie `SESSION`, `HttpOnly`, `SameSite=Lax`, `Secure` controlled by `APP_COOKIE_SECURE`. Active session count is exposed as `sl_active_sessions`.
- **CSRF.** The frontend reads the `XSRF-TOKEN` cookie and sends `X-XSRF-TOKEN` on every state-changing request. The frontend hits `/api/auth/csrf` to seed the cookie on app load and on logout.
- **Rate limiting.** Login attempts are limited per source IP via Bucket4j in-memory (5/min, 20/hour by default). State resets on restart — acceptable for a self-hosted single replica.
- **Logs.** JSON to stdout, rotated by Docker (`json-file`, 10MB × 5).
- **Backups.** Postgres is the only durable state; back up the `sharedledger` database with your usual tooling.

### Upgrading: Enable Banking credentials become per household

Applies only to instances that already had bank connections linked through the old instance-wide
env vars. Existing connections survive with **zero re-linking**, provided the env vars are still in
place at deploy time:

1. **Deploy with `ENABLE_BANKING_APP_ID` still set.** Migration `V030` adds
   `bank_connections.app_id`, and on the first boot after it the app stamps every existing
   connection that has none with that env var's value — the application it was actually authorized
   under. This is the bridge — without it the connections cannot be attributed and would need
   re-linking.
2. **An owner opens Settings → Banks and pastes the *same* application id and private key** into
   the credentials card (the key is the same file that was in `ENABLE_BANKING_PRIVATE_KEY`; both the
   full PEM and a bare base64 line are accepted). The ids match, so every connection keeps syncing
   untouched. Between steps 1 and 2 the connections show *credentials required* and background sync
   skips them quietly — an expected, brief gap; the status text says exactly what to do.
3. **Remove `ENABLE_BANKING_APP_ID` and `ENABLE_BANKING_PRIVATE_KEY`** — both now live in the
   household credentials. **Keep `ENABLE_BANKING_SECRET_KEY`** (it encrypts the stored credentials
   and sessions at rest; losing it makes both undecryptable) **and `ENABLE_BANKING_REDIRECT_URL`**
   (unchanged — it is instance configuration and is what the credentials card tells each household
   to register).

Multi-household instances repeat step 2 per household; each one may use its own EB application.

---

## 9. Preparing CSV files for bulk import

The Transactions, Snapshots, Movements, Portfolio, Lendings and Lending-payments importers each accept a UTF-8 CSV with a fixed header row, reachable from *Settings → Data import & export*. The importer parses the file, shows a preview (rows that would be inserted, skipped as duplicates, or rejected with row-level errors), and only writes after explicit confirmation. The same screen offers a single **Export all** ZIP that bundles one CSV per dataset (transactions, recurring templates, movements, snapshots, portfolio, lendings, lending payments); those files re-import cleanly.

The feeds capture different things:

- **Transactions** — day-to-day **income and expenses** (salary, rent, groceries, restaurants). This is the operational ledger used for budgets, savings rate, and the year-over-year analytics.
- **Snapshots** — periodic **net-worth recordings**: how much you currently hold in each asset class and what each liability still owes. This tracks **savings/wealth state** at a point in time.
- **Movements** — capital **moved into or out of** an asset class, or principal paid down on a liability. This tracks **what gets sent to (or taken from) savings**, separately from market gains.
- **Portfolio** — individual **investment holdings** as BUY/SELL lots (crypto, ETFs, stocks, funds). This drives per-holding valuation, P&L, and the snapshot prefill for market asset classes.
- **Lendings** and **Lending payments** — money lent to other people and the repayments received against it. Tracking-only; never touches transactions or net worth.
- **Assets** — named things you own (property, vehicles, valuables) with their dated value history; they add to net worth.
- **Liabilities** — named debts with their balance history and the amortizable flag/charge day; they subtract from net worth.
- **Amortization** — the schedule of each amortizable liability: its parts, rate revisions, prepayments, and generated instalments. Paired one-to-one with the Liabilities feed (import liabilities first, then amortization).

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

### Lendings CSV

Lendings and their payments use the **European-CSV dialect**: UTF-8, **semicolon (`;`) column separator**, **comma (`,`) decimal**, RFC-4180 quoting, ISO dates, positive amounts only. The first row is the literal header. This is exactly what *Prestado → Export lendings CSV* produces.

Header: `borrower_name;principal_amount;start_date;interest_type;annual_interest_rate;compounding_period;description;status`.

| Column | Required | Values |
| --- | --- | --- |
| `borrower_name` | yes | Free text (≤ 120 chars). The person you lent to. |
| `principal_amount` | yes | Positive decimal, comma decimal (e.g. `1000,00`). |
| `start_date` | yes | ISO date, `YYYY-MM-DD`. Date the money was lent. |
| `interest_type` | yes | `none`, `simple`, or `compound` (lowercase). |
| `annual_interest_rate` | conditional | Percentage points (e.g. `5,00` = 5 %). Required when `interest_type` is `simple` or `compound`; must be empty for `none`. |
| `compounding_period` | conditional | `monthly` or `yearly`. Required when `interest_type=compound`; must be empty otherwise. |
| `description` | no | Free text up to 500 characters. |
| `status` | no | `active`, `settled`, or `written_off`. Defaults to `active`. |

Rows whose `(borrower_name, start_date, principal_amount)` match an existing lending are skipped automatically.

Example:

```csv
borrower_name;principal_amount;start_date;interest_type;annual_interest_rate;compounding_period;description;status
Alice;1000,00;2025-01-01;none;;;Lending for car repair;active
Bob;5000,00;2024-06-15;simple;5,00;;;active
Carol;3000,00;2024-03-01;compound;4,50;monthly;Renovation lending;active
```

### Lending payments CSV

Header: `borrower_name;lending_start_date;payment_date;amount;description`.

| Column | Required | Values |
| --- | --- | --- |
| `borrower_name` | yes | Must match an existing lending's `borrower_name`. |
| `lending_start_date` | yes | Must match that lending's `start_date`; together with the borrower it locates the lending. |
| `payment_date` | yes | ISO date of the payment received. |
| `amount` | yes | Positive decimal, comma decimal. |
| `description` | no | Free text. |

If a row matches no lending it is rejected (`IMPORT_LENDING_UNKNOWN`); if the `(borrower_name, lending_start_date)` pair matches more than one lending the row is rejected as ambiguous (`IMPORT_LENDING_AMBIGUOUS`). Rows whose `(borrower_name, lending_start_date, payment_date, amount, description)` match an existing payment are skipped automatically.

Example:

```csv
borrower_name;lending_start_date;payment_date;amount;description
Alice;2025-01-01;2025-02-01;200,00;First repayment
Bob;2024-06-15;2024-07-15;500,00;
```

### Assets CSV

Header: `name;type;active;value_date;value`. One row per dated value; repeat the name across an asset's rows. Leave `value_date`/`value` empty to import an asset with no history yet.

| Column | Required | Values |
| --- | --- | --- |
| `name` | yes | Asset name; identifies the asset (reused if it already exists). |
| `type` | yes | `property`, `vehicle` or `other`. |
| `active` | yes | `true` / `false`. Inactive assets are excluded from new snapshots. |
| `value_date` | no | ISO date of a value observation. |
| `value` | if `value_date` set | Value on that date; comma decimal. |

An asset is matched by name; a value row already present (same date and value) is skipped, so re-importing an export is safe.

```csv
name;type;active;value_date;value
House;property;true;2026-01-15;450000,00
House;property;true;2026-06-20;500000,00
Car;vehicle;true;2026-01-01;18000,00
```

### Liabilities CSV

Header: `name;active;amortizable;charge_day;balance_date;balance`. One row per dated balance. Amortizable loans carry their schedule via the [Amortization CSV](#amortization-csv) and usually leave the balance columns empty here.

| Column | Required | Values |
| --- | --- | --- |
| `name` | yes | Liability name; identifies it (reused if it exists). |
| `active` | yes | `true` / `false`. |
| `amortizable` | yes | `true` when the balance is computed by a schedule. |
| `charge_day` | if `amortizable` | Day of month (1–31) the instalment is charged. |
| `balance_date` | no | ISO date of a manual balance (empty for amortizable). |
| `balance` | if `balance_date` set | Balance on that date; comma decimal. |

Matched by name; a balance row already present (same date and value) is skipped. **Import Liabilities before the Amortization feed** (the amortization rows reference existing liabilities).

```csv
name;active;amortizable;charge_day;balance_date;balance
Credit card;true;false;;2026-03-31;1200,00
Mortgage;true;true;28;;
```

### Amortization CSV

The schedule of each amortizable liability — one record per row, discriminated by `record_type`. Header:

`liability_name;part_label;record_type;date;method;principal;annual_rate;term_months;instalment;amount;mode;interest;resulting_balance;start_mode;anchor_date;anchor_balance`

- The referenced **liability must already exist** (import Liabilities first).
- `record_type` is one of `part`, `revision`, `prepayment`, `entry`; a part's row must come **before** its revisions/prepayments/entries. `part_label` ties a part's rows together within its liability.
- Columns used per record type: **part** → `method`, `principal`, `annual_rate`, `term_months`, `instalment`, `start_mode`, `anchor_date`, `anchor_balance`; **revision** → `annual_rate`; **prepayment** → `amount`, `mode`; **entry** (a generated instalment) → `principal`, `interest`, `resulting_balance`.
- Valid values: `method` = `french` / `german` / `interest_only` / `zero`; `mode` = `reduce_term` / `reduce_instalment`; `start_mode` = `current_balance` / `origin`.

Existing parts (matched by start date, principal and method), revisions, prepayments and entries are skipped, so re-importing an export is safe.

```csv
liability_name;part_label;record_type;date;method;principal;annual_rate;term_months;instalment;amount;mode;interest;resulting_balance;start_mode;anchor_date;anchor_balance
Mortgage;Part A;part;2026-01-01;french;200000,00;3,5000;300;;;;;;current_balance;;
Mortgage;Part A;revision;2027-01-01;;;4,0000;;;;;;;;;
Mortgage;Part A;prepayment;2026-06-01;;;;;;10000,00;reduce_term;;;;;
Mortgage;Part A;entry;2026-02-01;;299,55;;;;;;583,33;199700,45;;;
```

---

## 10. Local development (no Docker)

Backend:

```bash
cd backend
# Ensure DB_* env vars point to a real Postgres
APP_COOKIE_SECURE=false ADMIN_EMAIL=dev@example.com ADMIN_PASSWORD=devdevdev \
  ./gradlew bootRun
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
cd backend && ./gradlew test    # Testcontainers spins up Postgres
cd frontend && npm test
```

The backend suite boots one Spring context and runs its test classes four at a time against it
(`backend/src/test/resources/junit-platform.properties`). Classes that seed a shared stub bean, or that
write to a table keyed globally rather than per household — asset prices, fx rates, benchmark series —
must carry a matching `@ResourceLock`, otherwise they collide with whatever runs alongside them.

To keep the Postgres container alive between runs instead of starting a fresh one each time:

```bash
echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
```

The suite cleans and re-migrates the schema at startup, so a reused container still starts empty. The
container stays running afterwards; `docker rm -f` it when you want it gone. CI leaves this off and gets
a throwaway container.

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
