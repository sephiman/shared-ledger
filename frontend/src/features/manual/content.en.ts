import type { ManualChapter } from "./manualTypes";

export const MANUAL_CHAPTERS_EN: ManualChapter[] = [
  {
    id: "households",
    title: "Households, accounts & appearance",
    summary: "How households, roles, invitations, language, theme, and personal account settings work.",
    route: { to: "/settings", labelKey: "nav.settings" },
    sections: [
      {
        id: "households-model",
        heading: "Households and roles",
        paragraphs: [
          "All financial records—transactions, budgets, wealth, FIRE settings, and bank feeds—belong to a household rather than directly to a user. You can belong to one or more households and create new ones from Settings → Households.",
        ],
        bullets: [
          {
            term: "Owner vs. Member",
            text: "Owners manage household settings, currency, default locale, custom categories, invitations, member roles, Telegram notifications, AI features, auto-snapshots, and data wipes. Regular members can view and work with all financial data, link their own bank connections, and manage categorization rules.",
          },
          {
            term: "Switching & default household",
            text: "If you belong to more than one household, a switcher appears inside the avatar menu. You can mark one household as your default so every new login opens directly into it.",
          },
          {
            term: "At least one owner",
            text: "A household must always have at least one owner. To hand over ownership, promote another member to owner first before demoting yourself. A household cannot be deleted while any user still has it set as their default.",
          },
          {
            term: "Household settings & invitations",
            text: "Owners set the household name, currency, and default locale, and invite new members with one-time 14-day links (emailed automatically when SMTP is configured, or shown to copy manually; active invitations can be resent or revoked).",
          },
        ],
      },
      {
        id: "account-language-theme",
        heading: "Account, language, and theme",
        paragraphs: [
          "Your personal preferences live in the avatar menu and Settings.",
        ],
        bullets: [
          {
            term: "Language (EN / ES)",
            text: "Switching between English and Spanish in the avatar menu updates the interface immediately and saves the preference on your account, so automated emails (password reset, email confirmation) arrive in your chosen language.",
          },
          {
            term: "Theme (Light / Dark / OLED / System)",
            text: "Stored per browser in the avatar menu. System follows your OS light/dark setting; OLED uses pure black surfaces to save battery on OLED phone screens.",
          },
          {
            term: "Password, reset & email changes",
            text: "Changing password or email requires your current password. When email delivery (SMTP) is configured on the server, 'Forgot your password?' appears on the login page (60-minute single-use link), and changing your email sends a 60-minute confirmation link to the new address while notifying the old one; without SMTP, email changes apply immediately. Either way, all active sessions are signed out.",
          },
        ],
      },
    ],
  },
  {
    id: "dashboard",
    title: "Home dashboard",
    summary: "At-a-glance monthly and yearly totals, expense breakdown, and customizable summary tiles.",
    route: { to: "/dashboard", labelKey: "nav.dashboard" },
    sections: [
      {
        id: "home-overview",
        heading: "Summary tiles and charts",
        paragraphs: [
          "Home opens with the current month and current year income, expenses, and net totals alongside the monthly expense breakdown chart. Above them sit up to six quick-glance tiles:",
        ],
        bullets: [
          { term: "Savings rate", text: "Trailing 12-month savings rate with a 24-month sparkline, plus year-to-date and current-month rates." },
          { term: "Fixed cost of living", text: "Trailing 12-month average of recurring template expenses (per day and per year), compared with total spending including discretionary expenses." },
          { term: "Current wealth", text: "Latest net worth snapshot total (appears once you have at least one snapshot)." },
          { term: "Portfolio", text: "Live market value, invested cost, net invested ('own money'), and return for your holdings." },
          { term: "Money lent", text: "Total outstanding across active lendings and the top three balances." },
          { term: "Pending bank movements", text: "Count of unreviewed bank movements waiting in the inbox." },
        ],
        tip: "Data-dependent tiles appear only when they have data to show. Each user can hide any of the six tiles per account under Settings → Home panels.",
      },
    ],
  },
  {
    id: "transactions",
    title: "Transactions, categories & refunds",
    summary: "Day-to-day income and expenses, custom categories, URL-backed filters, and refunds.",
    route: { to: "/transactions", labelKey: "nav.transactions" },
    sections: [
      {
        id: "tx-recording",
        heading: "Recording and filtering transactions",
        paragraphs: [
          "Transactions track real operational income and expenses (capital transfers and investments belong in Wealth → Flows instead). The quick-add form highlights the categories you used most often in the last 30 days as one-tap chips.",
        ],
        bullets: [
          {
            term: "Filters, sorting & totals line",
            text: "Filter by date range, amount range (from/to), direction (income/expense), category, category group, or Refunds only, and click any column header (Date, Category, Description, Amount) to sort. Filters and sort stay in the URL so you can bookmark or reload a view. Above the list, the totals line sums the entire filtered set—not only the current page.",
          },
          {
            term: "Soft delete",
            text: "Deleting a transaction removes it from lists and calculations without destroying audit history.",
          },
        ],
      },
      {
        id: "categories",
        heading: "Global and custom categories",
        paragraphs: [
          "Every household shares a built-in catalog of income categories and eight expense groups (Home, Transport, Groceries, Shopping, Outings, Financial, Health, Personal). Owners can add custom categories in Settings → Custom categories.",
        ],
        bullets: [
          {
            term: "Essential flag",
            text: "Custom expense categories can be marked as essential so they count toward Cost of living and Lean FIRE (Essentials only).",
          },
          {
            term: "Renames vs. deletion",
            text: "Renaming a custom category or moving it between expense groups keeps all existing transactions, budgets, and templates intact. Deleting a custom category permanently deletes every transaction, budget, and recurring template linked to it.",
          },
        ],
      },
      {
        id: "refunds",
        heading: "Refunds",
        paragraphs: [
          "Money returned for a past purchase (a returned item, reimbursed ticket, or partial rebate) is recorded as a refund: an expense with a negative amount dated when the money came back, optionally linked to the original purchase.",
        ],
        bullets: [
          {
            term: "Why negative expense instead of income?",
            text: "Booking a refund as income inflates both income and spending. A negative expense nets the category and month where the money returned, keeping budgets, savings rate, and FIRE spending accurate without rewriting the original purchase.",
          },
          {
            term: "Three ways to record a refund",
            text: "1) In Transactions → Pending, choose Mark as refund on an incoming bank credit. 2) In Transactions, click Register refund on an existing expense. 3) Edit any transaction and toggle Is refund (useful for converting old reimbursement entries).",
          },
        ],
        tip: "Refunds can be partial or split across multiple returns; if linked refunds exceed the original purchase amount, the app shows a non-blocking warning.",
      },
    ],
  },
  {
    id: "recurring-budgets",
    title: "Recurring templates & budgets",
    summary: "Automated periodic transactions and monthly or annual spending targets.",
    route: { to: "/recurring", labelKey: "nav.recurring" },
    sections: [
      {
        id: "recurring",
        heading: "Recurring templates",
        paragraphs: [
          "Use Recurring to automate salary, rent, mortgage, subscriptions, and insurance on a weekly, monthly, or yearly schedule.",
        ],
        bullets: [
          {
            term: "End-of-month clamping",
            text: "A monthly template set to day 31 automatically falls on the last day of shorter months (e.g. Feb 28 or 29).",
          },
          {
            term: "Daily run & manual trigger",
            text: "A daily background job materializes due occurrences up to today without duplicating already-generated rows, and you can also trigger a template on demand from the list. Editing or deleting a generated transaction never alters its template, and pausing a template leaves past transactions untouched.",
          },
        ],
      },
      {
        id: "budgets",
        heading: "Budgets",
        paragraphs: [
          "Set monthly or annual expense limits per category under Budgets. Monthly budgets show how much is spent so far, the percentage consumed, color status (on track / approaching / over), and an extrapolated end-of-month pace based on days elapsed. Annual budgets appear in an annual matrix view.",
        ],
      },
    ],
  },
  {
    id: "wealth",
    title: "Wealth hub",
    summary: "Portfolio tracking, net-worth snapshots, capital flows, cash estimate, assets, loans, and money lent.",
    route: { to: "/networth", labelKey: "nav.wealth" },
    sections: [
      {
        id: "portfolio",
        heading: "Portfolio (Holdings, Allocation & Evolution)",
        paragraphs: [
          "Track crypto, ETFs, stocks, and funds at the lot level using FIFO accounting. When registering a buy, fill any two of quantity, unit price, and total price and the third is calculated automatically.",
        ],
        bullets: [
          {
            term: "Editing any trade",
            text: "Any field on a trade—including switching between Buy and Sell—can be edited inline. Every edit replays the entire holding ledger to ensure no sale ever exceeds available units and updates cost basis, realized P&L, XIRR, and evolution charts.",
          },
          {
            term: "Automatic pricing & Refresh now",
            text: "Search and link holdings to a market provider (CoinGecko/Binance for crypto; Yahoo Finance, EODHD, or Twelve Data for stocks/ETFs; ECB via Frankfurter for FX; funds are valued manually via snapshots). Trades in foreign currencies freeze the ECB FX rate at the trade date. A subtle amber dot marks prices past their freshness tolerance (3h for crypto, 4 calendar days for stocks/ETFs), delisted symbols show 'Not listed by provider' without losing stored history, and expanding a row shows the exact price timestamp. Use Refresh now to update your household's open series on demand (subject to a short cooldown).",
          },
          {
            term: "Closed positions, sorting & return bases",
            text: "Holdings worth under €1 (closed or dust) stay hidden behind the Show closed toggle without affecting totals, weights, or charts. All columns sort cleanly. Under Settings → Portfolio return percentages, choose whether P&L % divides by Open invested capital, Net invested ('own money'—when ≤ 0 you are playing with 'house money'), or Total capital turned over. The XIRR card reports annualized money-weighted return (or cumulative when history is under 1 year).",
          },
          {
            term: "TWR & Benchmarks",
            text: "Evolution plots daily market value alongside a Time-Weighted Return (TWR) curve that removes deposit/withdrawal timing distortion. You can optionally overlay S&P 500, MSCI World, Gold, and Bitcoin—all converted to EUR so currency swings never distort comparisons.",
          },
          {
            term: "One-shot Telegram price alerts",
            text: "When Telegram is active for the household, a bell icon on each open linked holding lets you set a target price (in the asset's native currency, up to 50 active alerts per household) with optional expiry. Alerts are checked after each price refresh, fire once, and auto-cancel if the position closes.",
          },
        ],
      },
      {
        id: "snapshots",
        heading: "Net-worth snapshots",
        paragraphs: [
          "Snapshots are the source of truth for net worth (Assets − Liabilities) at a chosen date, with at most one snapshot per date. The Allocation and Evolution tabs visualize asset breakdown and net worth over time—overlaying cumulative net contributions when Movements are recorded so you can see market revaluation.",
        ],
        bullets: [
          {
            term: "Smart prefill & typo guard",
            text: "New snapshots prefill market classes (Crypto, ETFs, Stocks, Funds) from your priced Portfolio, Cash from the flow-based Cash estimate, named Assets from their histories, and Loans from their balances/schedules. Any change larger than 50% requires explicit confirmation before saving. Saved snapshots are frozen and never rewritten retroactively.",
          },
          {
            term: "Auto-snapshot",
            text: "Owners can enable automatic daily, weekly, or monthly snapshots in Settings → Auto-snapshot.",
          },
        ],
      },
      {
        id: "flows-cash",
        heading: "Flows: Movements & Cash estimate",
        paragraphs: [
          "The Flows tab holds two sub-tabs: Movements and Cash.",
        ],
        bullets: [
          {
            term: "Movements",
            text: "Record capital contributions, withdrawals, and debt principal payments without polluting income or expenses. Recording movements unlocks contribution-vs-market revaluation on Evolution charts and powers the actual XIRR metric on FIRE.",
          },
          {
            term: "Cash (adjustments + flow estimate)",
            text: "Maintain a dated end-of-day Cash adjustment series (the truth—if multiple adjustments share a date, the latest created wins) while the app estimates your live Cash balance from flows dated strictly after the last adjustment: Transactions (+income / −expense), Lent (−lent / +repaid), and Movements (+withdrawal / −contribution / −debt payment). Each flow type has an on/off toggle, and editing Cash inside a snapshot re-anchors this series at the snapshot date.",
          },
          {
            term: "Linked-bank cash",
            text: "When bank accounts are linked, the Cash sub-tab lists each account's latest balance, a 'counts as cash' checkbox, and an Other cash field for wallets or unlinked accounts. After each daily bank balance read, the combined total automatically writes a Bank adjustment so your cash estimate re-anchors every day.",
          },
        ],
      },
      {
        id: "assets-liabilities-lent",
        heading: "Assets, Liabilities & Lent",
        paragraphs: [
          "Manage non-fungible wealth items and personal loans across three sibling tabs:",
        ],
        bullets: [
          {
            term: "Assets",
            text: "Named property, vehicles, or valuables with their own dated value series. Mark an asset inactive (e.g. sold) to keep history while excluding it from future snapshots.",
          },
          {
            term: "Simple & amortizable liabilities",
            text: "Track simple debts via a dated balance series, or configure multi-part amortizable loans (French, German, interest-only, or zero-interest; enter principal + rate + any one of term, end date, or instalment) with charge day, rate revisions, re-anchoring, prepayment simulation, and an autonomous monthly schedule that never duplicates transactions.",
          },
          {
            term: "Lent (tracking only)",
            text: "Track money lent to other people with 0%, simple, or compound interest, interest-first payment allocation, optional recurring repayment schedules, and Settled / Written off statuses (written-off lendings can be reopened). Lendings are intentionally excluded from net worth and transactions.",
          },
        ],
      },
    ],
  },
  {
    id: "analytics",
    title: "Analytics & Ask",
    summary: "Six analytical lenses on your spending, month-end projections, and natural-language filtering.",
    route: { to: "/analytics", labelKey: "nav.analytics" },
    sections: [
      {
        id: "analytics-tabs",
        heading: "The six Analytics tabs",
        paragraphs: [
          "Analytics shares a unified date range selector (3m, 6m, YTD, 1y, 2y, all time, or custom) and remembers your selected range per tab during the session.",
        ],
        bullets: [
          { term: "Daily", text: "Month spending calendar with day-level drill-down plus average spend by weekday and day-of-month." },
          { term: "Explorer", text: "Deep dive into a single category or group with monthly stats, optional prior-year overlay, top descriptions, and a stacked multi-category comparison chart. Shares filters in the URL." },
          { term: "Composition", text: "Income Allocation donut (including Saved share), Cost of living (essential vs. non-essential monthly averages), and Recurring vs. discretionary split." },
          { term: "Changes", text: "Top movers (biggest category increases/decreases vs. prior year or 6-month average) and a clickable category-by-month Heatmap (with an income toggle) that opens matching transactions." },
          { term: "Trends", text: "Year-over-year same-month comparison, year-by-year curves, trailing 12-month savings rate, and a 1–12 month category Forecast." },
          { term: "Money flow", text: "Sankey diagram tracing income sources into expense groups (or categories) and Saved/Deficit. When viewing the current month, a second Projection diagram estimates month-end totals using Full month average or Prorate remaining days per expense group—and lets you click any node to lock it to actuals only." },
        ],
      },
      {
        id: "ask-ai",
        heading: "Ask (optional AI filter assistant)",
        paragraphs: [
          "When AI features are enabled, an Ask box appears in Explorer and Transactions. Type a plain-language question (e.g. 'how much did we spend on restaurants last summer?'), and the assistant translates it into the page's real URL filters—routing automatically between Explorer and Transactions when needed, with one-click cross-links ('Better in Explorer →' / 'See transactions →').",
        ],
        tip: "Ask never sends amounts, transactions, or account identifiers to the AI provider—only your question, today's date, language, and category names.",
      },
    ],
  },
  {
    id: "fire",
    title: "FIRE projection & AI plan review",
    summary: "Monte Carlo financial independence planning, what-if sandbox, life events, and grounded AI diagnostics.",
    route: { to: "/fire", labelKey: "nav.fire" },
    sections: [
      {
        id: "fire-projection",
        heading: "Projection, tiers & scenarios",
        paragraphs: [
          "The FIRE page projects your qualifying investment wealth forward using Monte Carlo simulations. Every figure you enter is in today's money and is simulated in nominal euros, with a switch between Future money and Today's buying power on the chart.",
        ],
        bullets: [
          {
            term: "Estimated year (50% rule)",
            text: "A tier's estimated year is the first year in which at least half of all simulated paths reach the target. If fewer than half reach it within your horizon, the app reports 'Beyond [horizon]' and estimates the extra monthly savings needed.",
          },
          {
            term: "Tiers & Matrix",
            text: "Compare Lean FIRE (essentials only), FIRE (total spending), Fat FIRE (spending × multiplier), and an optional Own target across your return scenarios—switching between Reach, Post-FIRE drawdown survival, and Coast FIRE (stop contributing today).",
          },
          {
            term: "Coverage over time, Attribution & Levers",
            text: "Track how your coverage % evolved quarter by quarter, see an exact split of the last year's change (what you put in, what the market did, and changes in what you need), and inspect the six single-change levers ranked by impact.",
          },
        ],
      },
      {
        id: "fire-whatif-settings",
        heading: "What-If sandbox, life events & Settings",
        paragraphs: [
          "Use the What-If button next to the headline to test changes to monthly contribution, spending, horizon, SWR, one-time lump sums, or return shifts without touching your saved plan—or type a question in the AI What-If bar when AI is enabled.",
        ],
        bullets: [
          {
            term: "Plan & Advanced settings",
            text: "Configure your horizon, SWR, inflation, contribution source (manual, derived from savings, or derived from movements), spending lookback window (mean/median, spike exclusions, overrides—with a warning if the last 2 complete months have no recorded spending), return scenarios (plus Suggest from asset mix based on your latest snapshot), qualifying asset classes, Spanish capital-gains tax brackets, and future cash-flow events.",
          },
          {
            term: "Life-event templates",
            text: "Under Advanced → Cash-flow events, 'Add from template' lets you model events like the mortgage ending, state pension starting, part-time income, or one-off inflows/expenses—previewing their impact on your estimated year before saving.",
          },
        ],
      },
      {
        id: "fire-ai-review",
        heading: "AI Plan Review tab",
        paragraphs: [
          "When AI features are enabled, the AI Plan Review tab generates a structured, grounded review of your pre-computed FIRE summary (never raw transactions or IBANs). You can compare progress across runs, ask follow-up questions, export the review as Markdown, and load suggested changes directly into the What-If sandbox with one click.",
        ],
      },
    ],
  },
  {
    id: "banks",
    title: "Bank sync, pending inbox & rules",
    summary: "Connecting PSD2 banks via Enable Banking, reviewing incoming movements, and automating categorization.",
    route: { to: "/transactions?tab=pending", labelKey: "nav.transactions" },
    sections: [
      {
        id: "bank-linking",
        heading: "Linking bank accounts",
        paragraphs: [
          "Bank sync uses Enable Banking (read-only account information). Household owners configure the household's Enable Banking credentials in Settings → Banks; once saved, every household member can link their own bank accounts using their own bank authentication (SCA).",
        ],
        bullets: [
          {
            term: "Restricted Production checklist",
            text: "If using Enable Banking's free Restricted Production mode, make sure every bank account IBAN is whitelisted under 'Activate by linking accounts' in the Enable Banking Control Panel before linking in Shared Ledger—otherwise the connection will succeed with 0 accounts.",
          },
          {
            term: "Sync schedule & pausing",
            text: "Owners choose 1 or 2 daily sync times in Settings → Banks (within the bank's ~4 daily unattended call budget), and any connection manager or owner can trigger Sync now, pause an entire connection, or pause individual accounts.",
          },
          {
            term: "Consent expiry",
            text: "Bank permissions expire every 90–180 days. A banner appears 7 days ahead so you can re-link without losing history or existing rules.",
          },
        ],
      },
      {
        id: "pending-inbox",
        heading: "The Pending inbox",
        paragraphs: [
          "Synced bank movements land in Transactions → Pending for review—nothing enters your ledger automatically.",
        ],
        bullets: [
          { term: "Confirm or batch confirm", text: "Edit direction, category, or description inline (changes stay local until saved) and confirm individually or in bulk." },
          { term: "Replace possible duplicate", text: "If a bank movement matches a transaction you already entered manually, Replace merges the bank date, amount, and description into the existing transaction without creating a duplicate." },
          { term: "Split", text: "Divide a single charge (such as a supermarket trip with household goods) into two or more transactions across different categories, editing either amounts or percentages." },
          { term: "Merge", text: "Select two or more pending movements (even across different cards/banks, or a charge and its refund) and merge them into a single net transaction—or mark a zero-net pair as rejected." },
          { term: "Mark as movement / Mark as refund", text: "Send capital transfers straight to Wealth → Flows → Movements, or book incoming credits as negative-expense refunds." },
          { term: "Filters, grouping & restore", text: "Filter pending items by connection, search text, categorisation state (rule / AI / none), or possible duplicates, group by connection or category, and restore mistakenly rejected items from the Rejected filter." },
        ],
      },
      {
        id: "rules",
        heading: "Categorisation rules & AI suggestions",
        paragraphs: [
          "Confirming a pending item automatically learns a counterparty → category rule when no rule already matched. Manage, edit, filter, or bulk-delete rules under Settings → Categorisation rules (accessible to all household members).",
        ],
        bullets: [
          {
            term: "Apply rules",
            text: "Re-runs your current rules over pending items that have no rule suggestion yet.",
          },
          {
            term: "Suggest with AI & Suggest generalised rules",
            text: "When AI features are enabled, Suggest with AI fills remaining uncategorised inbox items (with IBANs masked and no amounts sent), and Suggest generalised rules on the Rules page proposes broader 'contains' rules to consolidate repetitive merchant variants—always checked for collisions before you accept.",
          },
        ],
      },
    ],
  },
  {
    id: "integrations-import",
    title: "Notifications, AI privacy & CSV import/export",
    summary: "Telegram alerts, AI privacy boundaries, bulk CSV import/export, and household data management.",
    route: { to: "/settings/import", labelKey: "import.title" },
    sections: [
      {
        id: "telegram",
        heading: "Telegram notifications",
        paragraphs: [
          "Owners can configure a Telegram bot token and chat ID in Settings → Notifications to receive formatted updates for transactions, snapshots, movements, lending payments, portfolio trades, recurring runs, new bank movements, consent expiry, and portfolio price alerts.",
        ],
        tip: "CSV bulk imports never trigger Telegram notifications, and a master toggle lets you pause all alerts without clearing your bot setup.",
      },
      {
        id: "ai-privacy",
        heading: "AI features & privacy",
        paragraphs: [
          "All AI features are optional, off by default, and controlled by a single owner toggle in Settings → AI features (only visible when the server operator configured an AI key).",
        ],
        bullets: [
          { term: "What never leaves your server", text: "AI features never send IBANs, user IDs, or your raw transaction ledger. Bank suggestion sends only counterparty, masked description, direction, and month (no amounts); Ask and FIRE What-If send only your question and category/template names; FIRE Plan Review sends only aggregated summary metrics." },
        ],
      },
      {
        id: "csv-wipe",
        heading: "CSV import, export & Danger zone",
        paragraphs: [
          "Under Settings → Data export / CSV import you can export a full backup of your household data or bulk-import Transactions, Recurring templates, Snapshots, Movements, Portfolio trades, Lendings, Lending payments, Assets, Liabilities, and Amortization schedules.",
          "Owners also have a confirmation-gated Delete all data action in Settings → Danger zone to wipe all financial records while keeping the household and its members.",
        ],
      },
    ],
  },
];
