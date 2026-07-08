import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useAssetClasses, useCategories, type AssetClass, type Category } from "@/api/catalog";
import { useLiabilities } from "@/api/networth";
import { categoryLabel } from "@/lib/categoryLabel";

type Dataset = "transactions" | "snapshots" | "movements" | "recurring" | "loans" | "loan_payments" | "portfolio" | "assets" | "liabilities" | "amortization";

const ASSET_HEADER = "name;type;active;value_date;value";
const LIABILITY_HEADER = "name;active;amortizable;charge_day;balance_date;balance";
const AMORT_HEADER = "liability_name;part_label;record_type;date;method;principal;annual_rate;term_months;instalment;amount;mode;interest;resulting_balance;start_mode;anchor_date;anchor_balance";

const TX_HEADER = "date;direction;category_code;amount;description;created_at;updated_at";
const PORTFOLIO_HEADER = "type;asset_class;symbol;label;native_currency;isin;provider;provider_symbol;traded_on;quantity;unit_price;cost_currency;fee;note";
const SNAP_HEADER = "date;note;kind;key;value";
const MOV_HEADER = "date;type;asset_class_code;liability_name;amount;description;created_at";
const REC_HEADER = "direction;category_code;amount;description;cadence;day_of_week;day_of_month;month_of_year;day_of_month_yearly;start_date;end_date;active";
const LOAN_HEADER = "borrower_name;principal_amount;start_date;interest_type;annual_interest_rate;compounding_period;description;status";
const LOAN_PAYMENT_HEADER = "borrower_name;loan_start_date;payment_date;amount;description";

export function CsvFormatHelp({ dataset }: { dataset: Dataset }) {
  const { t } = useTranslation();
  return (
    <details className="mt-2 text-sm">
      <summary className="cursor-pointer select-none text-primary">{t("import.format.toggle")}</summary>
      <div className="mt-2 space-y-3 rounded border border-border bg-gray-50 p-3 dark:bg-gray-900/40">
        <p className="rounded border border-sky-300 bg-sky-50 p-2 text-xs text-sky-900 dark:border-sky-700 dark:bg-sky-900/30 dark:text-sky-100">
          {t("import.format.ai_hint")}
        </p>
        {dataset === "transactions" && <TransactionFormat />}
        {dataset === "snapshots" && <SnapshotFormat />}
        {dataset === "movements" && <MovementFormat />}
        {dataset === "recurring" && <RecurringFormat />}
        {dataset === "loans" && <LoanFormat />}
        {dataset === "loan_payments" && <LoanPaymentFormat />}
        {dataset === "portfolio" && <PortfolioFormat />}
        {dataset === "assets" && <AssetFormat />}
        {dataset === "liabilities" && <LiabilityNamedFormat />}
        {dataset === "amortization" && <AmortizationFormat />}
      </div>
    </details>
  );
}

function AssetFormat() {
  const { t } = useTranslation();
  const example = [
    ASSET_HEADER,
    "House;property;true;2026-01-15;450000,00",
    "House;property;true;2026-06-20;500000,00",
    "Car;vehicle;true;2026-01-01;18000,00",
  ].join("\n");
  return (
    <>
      <p>{t("import.format.assets.intro")}</p>
      <Section title={t("import.format.headers_label")}><CodeBlock>{ASSET_HEADER}</CodeBlock></Section>
      <Section title={t("import.format.columns_label")}>
        <ColumnList items={[
          { name: "name", req: true, desc: t("import.format.assets.name") },
          { name: "type", req: true, desc: t("import.format.assets.type") },
          { name: "active", req: true, desc: t("import.format.assets.active") },
          { name: "value_date", req: false, desc: t("import.format.assets.value_date") },
          { name: "value", req: false, desc: t("import.format.assets.value") },
        ]} />
      </Section>
      <Section title={t("import.format.example_label")}><CodeBlock>{example}</CodeBlock></Section>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.assets.dedup")}</p>
    </>
  );
}

function LiabilityNamedFormat() {
  const { t } = useTranslation();
  const example = [
    LIABILITY_HEADER,
    "Credit card;true;false;;2026-03-31;1200,00",
    "Mortgage;true;true;28;;",
  ].join("\n");
  return (
    <>
      <p>{t("import.format.liab.intro")}</p>
      <Section title={t("import.format.headers_label")}><CodeBlock>{LIABILITY_HEADER}</CodeBlock></Section>
      <Section title={t("import.format.columns_label")}>
        <ColumnList items={[
          { name: "name", req: true, desc: t("import.format.liab.name") },
          { name: "active", req: true, desc: t("import.format.liab.active") },
          { name: "amortizable", req: true, desc: t("import.format.liab.amortizable") },
          { name: "charge_day", req: false, desc: t("import.format.liab.charge_day") },
          { name: "balance_date", req: false, desc: t("import.format.liab.balance_date") },
          { name: "balance", req: false, desc: t("import.format.liab.balance") },
        ]} />
      </Section>
      <Section title={t("import.format.example_label")}><CodeBlock>{example}</CodeBlock></Section>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.liab.dedup")}</p>
    </>
  );
}

function AmortizationFormat() {
  const { t } = useTranslation();
  const example = [
    AMORT_HEADER,
    "Mortgage;Part A;part;2026-01-01;french;200000,00;3,5000;300;;;;;;current_balance;;",
    "Mortgage;Part A;revision;2027-01-01;;;4,0000;;;;;;;;;",
    "Mortgage;Part A;prepayment;2026-06-01;;;;;;10000,00;reduce_term;;;;;",
    "Mortgage;Part A;entry;2026-02-01;;299,55;;;;;;583,33;199700,45;;;",
  ].join("\n");
  return (
    <>
      <p>{t("import.format.amort.intro")}</p>
      <Section title={t("import.format.headers_label")}><CodeBlock>{AMORT_HEADER}</CodeBlock></Section>
      <Section title={t("import.format.columns_label")}>
        <ColumnList items={[
          { name: "liability_name", req: true, desc: t("import.format.amort.liability_name") },
          { name: "part_label", req: true, desc: t("import.format.amort.part_label") },
          { name: "record_type", req: true, desc: t("import.format.amort.record_type") },
          { name: "date", req: true, desc: t("import.format.amort.date") },
          { name: "…", req: false, desc: t("import.format.amort.fields") },
        ]} />
      </Section>
      <Section title={t("import.format.amort.catalogs_label")}>
        <ul className="space-y-1 text-xs">
          <li><code className="font-mono">record_type</code>: part / revision / prepayment / entry</li>
          <li><code className="font-mono">method</code>: french / german / interest_only / zero</li>
          <li><code className="font-mono">mode</code>: reduce_term / reduce_instalment</li>
          <li><code className="font-mono">start_mode</code>: current_balance / origin</li>
        </ul>
      </Section>
      <Section title={t("import.format.example_label")}><CodeBlock>{example}</CodeBlock></Section>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.amort.dedup")}</p>
    </>
  );
}

function LoanFormat() {
  const { t } = useTranslation();
  const example = [
    LOAN_HEADER,
    "Alice;1000,00;2025-01-01;none;;;Loan for car repair;active",
    "Bob;5000,00;2024-06-15;simple;5,00;;;active",
    "Carol;3000,00;2024-03-01;compound;4,50;monthly;Renovation loan;active",
  ].join("\n");
  return (
    <>
      <p>{t("import.format.loan.intro")}</p>
      <Section title={t("import.format.headers_label")}>
        <CodeBlock>{LOAN_HEADER}</CodeBlock>
      </Section>
      <Section title={t("import.format.columns_label")}>
        <ColumnList
          items={[
            { name: "borrower_name", req: true, desc: t("import.format.loan.borrower_name") },
            { name: "principal_amount", req: true, desc: t("import.format.loan.principal_amount") },
            { name: "start_date", req: true, desc: t("import.format.loan.start_date") },
            { name: "interest_type", req: true, desc: t("import.format.loan.interest_type") },
            { name: "annual_interest_rate", req: false, desc: t("import.format.loan.annual_interest_rate") },
            { name: "compounding_period", req: false, desc: t("import.format.loan.compounding_period") },
            { name: "description", req: false, desc: t("import.format.loan.description") },
            { name: "status", req: false, desc: t("import.format.loan.status") },
          ]}
        />
      </Section>
      <Section title={t("import.format.loan.catalogs_label")}>
        <ul className="space-y-1 text-xs">
          <li><code className="font-mono">interest_type</code>: none / simple / compound</li>
          <li><code className="font-mono">compounding_period</code>: monthly / yearly</li>
          <li><code className="font-mono">status</code>: active / settled / written_off</li>
        </ul>
      </Section>
      <Section title={t("import.format.example_label")}>
        <CodeBlock>{example}</CodeBlock>
      </Section>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.loan.dedup")}</p>
    </>
  );
}

function LoanPaymentFormat() {
  const { t } = useTranslation();
  const example = [
    LOAN_PAYMENT_HEADER,
    "Alice;2025-01-01;2025-02-01;200,00;First repayment",
    "Bob;2024-06-15;2024-07-15;500,00;",
  ].join("\n");
  return (
    <>
      <p>{t("import.format.loan_payment.intro")}</p>
      <Section title={t("import.format.headers_label")}>
        <CodeBlock>{LOAN_PAYMENT_HEADER}</CodeBlock>
      </Section>
      <Section title={t("import.format.columns_label")}>
        <ColumnList
          items={[
            { name: "borrower_name", req: true, desc: t("import.format.loan_payment.borrower_name") },
            { name: "loan_start_date", req: true, desc: t("import.format.loan_payment.loan_start_date") },
            { name: "payment_date", req: true, desc: t("import.format.loan_payment.payment_date") },
            { name: "amount", req: true, desc: t("import.format.loan_payment.amount") },
            { name: "description", req: false, desc: t("import.format.loan_payment.description") },
          ]}
        />
      </Section>
      <Section title={t("import.format.example_label")}>
        <CodeBlock>{example}</CodeBlock>
      </Section>
      <p className="rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-100">
        {t("import.format.loan_payment.matching_note")}
      </p>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.loan_payment.dedup")}</p>
    </>
  );
}

function TransactionFormat() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const { data: categories } = useCategories(household.householdId);
  const sorted = [...(categories ?? [])].sort(byKindThenSort);
  const income = sorted.filter((c) => c.kind === "income");
  const expense = sorted.filter((c) => c.kind === "expense");

  const example = [
    TX_HEADER,
    income[0] && `2026-05-01;income;${income[0].code};3200,00;May payroll;;`,
    expense[0] && `2026-05-03;expense;${expense[0].code};42,30;Sample description;;`,
  ].filter(Boolean).join("\n");

  return (
    <>
      <p>{t("import.format.tx.intro")}</p>
      <Section title={t("import.format.headers_label")}>
        <CodeBlock>{TX_HEADER}</CodeBlock>
      </Section>
      <Section title={t("import.format.columns_label")}>
        <ColumnList
          items={[
            { name: "date", req: true, desc: t("import.format.tx.date") },
            { name: "direction", req: true, desc: t("import.format.tx.direction") },
            { name: "category_code", req: true, desc: t("import.format.tx.category_code") },
            { name: "amount", req: true, desc: t("import.format.tx.amount") },
            { name: "description", req: false, desc: t("import.format.tx.description") },
            { name: "created_at", req: false, desc: t("import.format.tx.created_at") },
            { name: "updated_at", req: false, desc: t("import.format.tx.updated_at") },
          ]}
        />
      </Section>
      <Section title={t("import.format.tx.income_codes_label")}>
        <CategoryCodeList items={income} />
      </Section>
      <Section title={t("import.format.tx.expense_codes_label")}>
        <CategoryCodeList items={expense} />
      </Section>
      <p className="rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-100">
        {t("import.format.tx.unknown_category_note")}
      </p>
      <Section title={t("import.format.example_label")}>
        <CodeBlock>{example}</CodeBlock>
      </Section>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.tx.dedup")}</p>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.tx.in_file_dedup")}</p>
    </>
  );
}

function SnapshotFormat() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const { data: assetClasses } = useAssetClasses();
  const { data: liabilities } = useLiabilities(household.householdId);
  const assets = [...(assetClasses ?? [])].sort((a, b) => a.sortOrder - b.sortOrder);
  const activeLiabilities = (liabilities ?? []).filter((l) => l.active);

  const example = [
    SNAP_HEADER,
    ...assets.map((a, i) => `2026-04-30;April close;asset;${a.code};${sampleAmount(i)}`),
    ...activeLiabilities.map((l) => `2026-04-30;April close;liability;${l.name};0,00`),
  ].join("\n");

  return (
    <>
      <p>{t("import.format.snap.intro")}</p>
      <Section title={t("import.format.headers_label")}>
        <CodeBlock>{SNAP_HEADER}</CodeBlock>
      </Section>
      <Section title={t("import.format.columns_label")}>
        <ColumnList
          items={[
            { name: "date", req: true, desc: t("import.format.snap.date") },
            { name: "note", req: false, desc: t("import.format.snap.note") },
            { name: "kind", req: true, desc: t("import.format.snap.kind") },
            { name: "key", req: true, desc: t("import.format.snap.key") },
            { name: "value", req: true, desc: t("import.format.snap.value") },
          ]}
        />
      </Section>
      <Section title={t("import.format.snap.asset_codes_label")}>
        <AssetCodeList items={assets} />
        <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{t("import.format.snap.asset_codes_note")}</p>
      </Section>
      <Section title={t("import.format.snap.liabilities_label")}>
        <LiabilityNameList items={activeLiabilities} />
        <p className="mt-2 rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-100">{t("import.format.snap.liabilities_note")}</p>
      </Section>
      <Section title={t("import.format.example_label")}>
        <CodeBlock>{example}</CodeBlock>
      </Section>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.snap.rules")}</p>
    </>
  );
}

function MovementFormat() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const { data: assetClasses } = useAssetClasses();
  const { data: liabilities } = useLiabilities(household.householdId);
  const assets = [...(assetClasses ?? [])].sort((a, b) => a.sortOrder - b.sortOrder);
  const activeLiabilities = (liabilities ?? []).filter((l) => l.active);

  const exampleRows: string[] = [MOV_HEADER];
  if (assets[0]) exampleRows.push(`2026-05-02;contribution;${assets[0].code};;500,00;Monthly DCA;`);
  if (assets[0]) exampleRows.push(`2026-05-10;withdrawal;${assets[0].code};;200,00;Sample withdrawal;`);
  if (activeLiabilities[0]) exampleRows.push(`2026-05-15;debt_payment;;${activeLiabilities[0].name};950,00;May installment;`);

  return (
    <>
      <p>{t("import.format.mov.intro")}</p>
      <Section title={t("import.format.headers_label")}>
        <CodeBlock>{MOV_HEADER}</CodeBlock>
      </Section>
      <Section title={t("import.format.columns_label")}>
        <ColumnList
          items={[
            { name: "date", req: true, desc: t("import.format.mov.date") },
            { name: "type", req: true, desc: t("import.format.mov.type") },
            { name: "asset_class_code", req: false, desc: t("import.format.mov.asset_class_code") },
            { name: "liability_name", req: false, desc: t("import.format.mov.liability_name") },
            { name: "amount", req: true, desc: t("import.format.mov.amount") },
            { name: "description", req: false, desc: t("import.format.mov.description") },
            { name: "created_at", req: false, desc: t("import.format.mov.created_at") },
          ]}
        />
      </Section>
      <Section title={t("import.format.snap.asset_codes_label")}>
        <AssetCodeList items={assets} />
        <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{t("import.format.snap.asset_codes_note")}</p>
      </Section>
      <Section title={t("import.format.snap.liabilities_label")}>
        <LiabilityNameList items={activeLiabilities} />
        <p className="mt-2 rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-100">{t("import.format.snap.liabilities_note")}</p>
      </Section>
      <Section title={t("import.format.example_label")}>
        <CodeBlock>{exampleRows.join("\n")}</CodeBlock>
      </Section>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.mov.dedup")}</p>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.mov.in_file_dedup")}</p>
    </>
  );
}

function RecurringFormat() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const { data: categories } = useCategories(household.householdId);
  const sorted = [...(categories ?? [])].sort(byKindThenSort);
  const income = sorted.filter((c) => c.kind === "income");
  const expense = sorted.filter((c) => c.kind === "expense");

  const examples = [
    REC_HEADER,
    expense[0] && `expense;${expense[0].code};45,00;Netflix;monthly;;15;;;2026-01-15;;true`,
    expense[1] && `expense;${expense[1].code};1200,00;Rent;monthly;;1;;;2026-01-01;;true`,
    income[0] && `income;${income[0].code};3200,00;Payroll;monthly;;25;;;2026-01-25;;true`,
  ].filter(Boolean).join("\n");

  return (
    <>
      <p>{t("import.format.rec.intro")}</p>
      <Section title={t("import.format.headers_label")}>
        <CodeBlock>{REC_HEADER}</CodeBlock>
      </Section>
      <Section title={t("import.format.columns_label")}>
        <ColumnList
          items={[
            { name: "direction", req: true, desc: t("import.format.rec.direction") },
            { name: "category_code", req: true, desc: t("import.format.rec.category_code") },
            { name: "amount", req: true, desc: t("import.format.rec.amount") },
            { name: "description", req: false, desc: t("import.format.rec.description") },
            { name: "cadence", req: true, desc: t("import.format.rec.cadence") },
            { name: "day_of_week", req: false, desc: t("import.format.rec.day_of_week") },
            { name: "day_of_month", req: false, desc: t("import.format.rec.day_of_month") },
            { name: "month_of_year", req: false, desc: t("import.format.rec.month_of_year") },
            { name: "day_of_month_yearly", req: false, desc: t("import.format.rec.day_of_month_yearly") },
            { name: "start_date", req: true, desc: t("import.format.rec.start_date") },
            { name: "end_date", req: false, desc: t("import.format.rec.end_date") },
            { name: "active", req: true, desc: t("import.format.rec.active") },
          ]}
        />
      </Section>
      <Section title={t("import.format.tx.income_codes_label")}>
        <CategoryCodeList items={income} />
      </Section>
      <Section title={t("import.format.tx.expense_codes_label")}>
        <CategoryCodeList items={expense} />
      </Section>
      <p className="rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-100">
        {t("import.format.tx.unknown_category_note")}
      </p>
      <Section title={t("import.format.example_label")}>
        <CodeBlock>{examples}</CodeBlock>
      </Section>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.rec.dedup")}</p>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.rec.in_file_dedup")}</p>
    </>
  );
}

function PortfolioFormat() {
  const { t } = useTranslation();
  const example = [
    PORTFOLIO_HEADER,
    "BUY;crypto;BTC;Bitcoin;EUR;;coingecko;bitcoin;2025-11-15;0,05;62000,00;EUR;5,00;DCA",
    "BUY;etf;WEBN;Amundi Prime All Country;EUR;IE0009OA6R05;yahoo;WEBN.DE;2026-01-10;120;9,87;EUR;1,50;",
    "BUY;etf;WEBN;Amundi Prime All Country;EUR;IE0009OA6R05;yahoo;WEBN.DE;2026-02-10;80;10,12;EUR;1,50;",
    "SELL;etf;WEBN;Amundi Prime All Country;EUR;IE0009OA6R05;yahoo;WEBN.DE;2026-05-10;50;11,05;EUR;1,50;Rebalancing",
    "BUY;stock;AAPL;Apple;USD;US0378331005;;;2026-03-05;10;180,00;USD;;",
    "BUY;fund;MSCIW;World index fund;EUR;;;;2026-01-31;15,5;95,20;EUR;;",
  ].join("\n");
  return (
    <>
      <p>{t("import.format.portfolio.intro")}</p>
      <Section title={t("import.format.headers_label")}>
        <CodeBlock>{PORTFOLIO_HEADER}</CodeBlock>
      </Section>
      <Section title={t("import.format.columns_label")}>
        <ColumnList
          items={[
            { name: "type", req: false, desc: t("import.format.portfolio.type") },
            { name: "asset_class", req: true, desc: t("import.format.portfolio.asset_class") },
            { name: "symbol", req: true, desc: t("import.format.portfolio.symbol") },
            { name: "label", req: false, desc: t("import.format.portfolio.label") },
            { name: "native_currency", req: false, desc: t("import.format.portfolio.native_currency") },
            { name: "isin", req: false, desc: t("import.format.portfolio.isin") },
            { name: "provider", req: false, desc: t("import.format.portfolio.provider") },
            { name: "provider_symbol", req: false, desc: t("import.format.portfolio.provider_symbol") },
            { name: "traded_on", req: true, desc: t("import.format.portfolio.traded_on") },
            { name: "quantity", req: true, desc: t("import.format.portfolio.quantity") },
            { name: "unit_price", req: true, desc: t("import.format.portfolio.unit_price") },
            { name: "cost_currency", req: false, desc: t("import.format.portfolio.cost_currency") },
            { name: "fee", req: false, desc: t("import.format.portfolio.fee") },
            { name: "note", req: false, desc: t("import.format.portfolio.note") },
          ]}
        />
      </Section>
      <Section title={t("import.format.portfolio.catalogs_label")}>
        <ul className="space-y-1 text-xs">
          <li><code className="font-mono">type</code>: BUY / SELL</li>
          <li><code className="font-mono">asset_class</code>: crypto / etf / stock / fund</li>
          <li><code className="font-mono">provider</code>: coingecko / yahoo / eodhd / twelvedata</li>
        </ul>
      </Section>
      <Section title={t("import.format.example_label")}>
        <CodeBlock>{example}</CodeBlock>
      </Section>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.portfolio.grouping")}</p>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.portfolio.dedup")}</p>
    </>
  );
}

function byKindThenSort(a: Category, b: Category) {
  if (a.kind !== b.kind) return a.kind === "income" ? -1 : 1;
  if (a.sortOrder !== b.sortOrder) return a.sortOrder - b.sortOrder;
  return a.code.localeCompare(b.code);
}

function sampleAmount(i: number): string {
  const samples = ["5400,00", "18250,75", "0,00", "0,00", "0,00", "0,00", "0,00", "0,00"];
  return samples[i] ?? "0,00";
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{title}</p>
      {children}
    </div>
  );
}

function CodeBlock({ children }: { children: string }) {
  return (
    <pre className="overflow-x-auto rounded bg-gray-100 p-2 font-mono text-xs text-gray-800 dark:bg-gray-900 dark:text-gray-200">
      {children}
    </pre>
  );
}

function ColumnList({ items }: { items: { name: string; req: boolean; desc: string }[] }) {
  const { t } = useTranslation();
  return (
    <ul className="space-y-1">
      {items.map((it) => (
        <li key={it.name}>
          <code className="font-mono text-xs">{it.name}</code>
          <span className="ml-1 text-xs text-gray-500 dark:text-gray-400">
            ({it.req ? t("import.format.required") : t("import.format.optional")})
          </span>{" "}
          — {it.desc}
        </li>
      ))}
    </ul>
  );
}

function CategoryCodeList({ items }: { items: Category[] }) {
  const { t } = useTranslation();
  if (items.length === 0) {
    return <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.none_yet")}</p>;
  }
  return (
    <ul className="grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-3">
      {items.map((c) => (
        <li key={c.code} className="text-xs">
          <code className="font-mono">{c.code}</code> — {categoryLabel(c, t)}
          {c.custom && (
            <span className="ml-1 rounded bg-gray-200 px-1 text-[10px] uppercase tracking-wide text-gray-600 dark:bg-gray-700 dark:text-gray-300">
              {t("import.format.custom_badge")}
            </span>
          )}
        </li>
      ))}
    </ul>
  );
}

function AssetCodeList({ items }: { items: AssetClass[] }) {
  const { t } = useTranslation();
  if (items.length === 0) {
    return <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.none_yet")}</p>;
  }
  return (
    <ul className="grid grid-cols-2 gap-x-4 sm:grid-cols-3">
      {items.map((c) => (
        <li key={c.code} className="text-xs">
          <code className="font-mono">{c.code}</code> — {t(`asset.${c.code}`, c.code)}
        </li>
      ))}
    </ul>
  );
}

function LiabilityNameList({ items }: { items: { id: string; name: string }[] }) {
  const { t } = useTranslation();
  if (items.length === 0) {
    return <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.snap.no_liabilities")}</p>;
  }
  return (
    <ul className="grid grid-cols-1 gap-x-4 sm:grid-cols-2 lg:grid-cols-3">
      {items.map((l) => (
        <li key={l.id} className="text-xs">
          <code className="font-mono">{l.name}</code>
        </li>
      ))}
    </ul>
  );
}
