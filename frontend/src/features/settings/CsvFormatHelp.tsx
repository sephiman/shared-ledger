import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useAssetClasses, useCategories, type AssetClass, type Category } from "@/api/catalog";
import { useLiabilities } from "@/api/networth";
import { categoryLabel } from "@/lib/categoryLabel";

type Dataset = "transactions" | "snapshots" | "movements";

const TX_HEADER = "date;direction;category_code;amount;description;created_at;updated_at";
const SNAP_HEADER = "date;note;kind;key;value";
const MOV_HEADER = "date;type;asset_class_code;liability_name;amount;description;created_at";

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
      </div>
    </details>
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
      <Section title={t("import.format.example_label")}>
        <CodeBlock>{example}</CodeBlock>
      </Section>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.tx.dedup")}</p>
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
      </Section>
      <Section title={t("import.format.snap.liabilities_label")}>
        <LiabilityNameList items={activeLiabilities} />
        <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{t("import.format.snap.liabilities_note")}</p>
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
      </Section>
      <Section title={t("import.format.snap.liabilities_label")}>
        <LiabilityNameList items={activeLiabilities} />
        <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{t("import.format.snap.liabilities_note")}</p>
      </Section>
      <Section title={t("import.format.example_label")}>
        <CodeBlock>{exampleRows.join("\n")}</CodeBlock>
      </Section>
      <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.format.mov.dedup")}</p>
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
