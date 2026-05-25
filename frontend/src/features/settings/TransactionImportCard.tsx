import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { asApiError } from "@/api/client";
import { useExecuteTransactions, usePreviewTransactions, type ExecuteResult, type PreviewSummary } from "@/api/import";
import { Button, Card, CardBody, CardHeader, FieldError } from "@/components/ui/primitives";
import { FilePicker } from "./FilePicker";

export function TransactionImportCard() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<PreviewSummary | null>(null);
  const [result, setResult] = useState<ExecuteResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const previewMut = usePreviewTransactions(household.householdId);
  const executeMut = useExecuteTransactions(household.householdId);

  function reset() {
    setFile(null);
    setPreview(null);
    setResult(null);
    setError(null);
  }

  async function runPreview() {
    if (!file) return;
    setError(null);
    setResult(null);
    try {
      const p = await previewMut.mutateAsync(file);
      setPreview(p);
    } catch (err) {
      setPreview(null);
      const api = asApiError(err);
      setError(t(`errors.${api.code}`, api.message));
    }
  }

  async function runExecute() {
    if (!file) return;
    setError(null);
    try {
      const r = await executeMut.mutateAsync(file);
      setResult(r);
      setPreview(null);
    } catch (err) {
      const api = asApiError(err);
      setError(t(`errors.${api.code}`, api.message));
    }
  }

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("import.transactions")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("import.transactions_description")}</p>
      </CardHeader>
      <CardBody className="space-y-3">
        {!result && (
          <>
            <FilePicker
              file={file}
              onChange={(f) => { setFile(f); setPreview(null); setError(null); }}
            />
            {!preview && (
              <Button onClick={runPreview} disabled={!file || previewMut.isPending}>
                {previewMut.isPending ? t("common.loading") : t("import.validate")}
              </Button>
            )}
          </>
        )}

        <FieldError message={error} />

        {preview && !result && <PreviewPanel preview={preview} dataset="transactions" />}

        {preview && !result && preview.errorCount === 0 && (
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={reset}>{t("common.cancel")}</Button>
            <Button onClick={runExecute} disabled={executeMut.isPending}>
              {executeMut.isPending ? t("common.loading") : t("import.confirm")}
            </Button>
          </div>
        )}

        {result && <ResultPanel result={result} onReset={reset} />}
      </CardBody>
    </Card>
  );
}

function PreviewPanel({ preview, dataset }: { preview: PreviewSummary; dataset: "transactions" | "snapshots" | "movements" }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-3 rounded border border-border p-3 text-sm">
      <div className="grid grid-cols-2 gap-2 md:grid-cols-4">
        <Stat label={t("import.total_rows")} value={preview.totalRows} />
        <Stat label={t("import.would_insert")} value={preview.wouldInsert} />
        <Stat label={t("import.would_skip")} value={preview.wouldSkip} />
        {dataset === "snapshots" && <Stat label={t("import.would_replace")} value={preview.wouldReplace} />}
      </div>
      {(preview.dateFrom || preview.dateTo) && (
        <p className="text-gray-600 dark:text-gray-300">{t("import.date_range")}: {preview.dateFrom} → {preview.dateTo}</p>
      )}
      {dataset === "transactions" && (
        <p className="text-gray-600 dark:text-gray-300">
          {t("common.income")}: {preview.sumIncome ?? "0,00"} · {t("common.expense")}: {preview.sumExpense ?? "0,00"}
        </p>
      )}
      {dataset === "snapshots" && (
        <p className="text-gray-600 dark:text-gray-300">
          {t("networth.total_assets")}: {preview.sumAssets ?? "0,00"} · {t("networth.total_liabilities")}: {preview.sumLiabilities ?? "0,00"}
        </p>
      )}
      {dataset === "movements" && (
        <p className="text-gray-600 dark:text-gray-300">
          {t("networth.contribution")}: {preview.sumContributions ?? "0,00"} · {t("networth.withdrawal")}: {preview.sumWithdrawals ?? "0,00"} · {t("networth.debt_payment")}: {preview.sumDebtPayments ?? "0,00"}
        </p>
      )}
      {preview.errorCount > 0 && (
        <div>
          <p className="font-medium text-red-600">{t("import.errors_found", { count: preview.errorCount })}</p>
          {preview.truncatedErrors && <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.errors_truncated")}</p>}
          <div className="mt-1 max-h-64 overflow-auto">
            <table className="w-full text-xs">
              <thead className="text-left text-gray-500 dark:text-gray-400">
                <tr>
                  <th className="py-1">{t("import.row")}</th>
                  <th>{t("import.field")}</th>
                  <th>{t("import.message")}</th>
                </tr>
              </thead>
              <tbody>
                {preview.errors.map((e, i) => (
                  <tr key={i} className="border-t border-border">
                    <td className="py-1">{e.row}</td>
                    <td>{e.field ?? "—"}</td>
                    <td>{t(`errors.${e.code}`, e.code)}{e.value ? ` (“${e.value}”)` : ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
      {preview.skippedRows.length > 0 && (
        <SkippedRowsTable rows={preview.skippedRows} truncated={preview.truncatedSkipped} totalCount={preview.wouldSkip} />
      )}
    </div>
  );
}

function SkippedRowsTable({ rows, truncated, totalCount }: { rows: { row: number; summary: string }[]; truncated: boolean; totalCount: number }) {
  const { t } = useTranslation();
  return (
    <div>
      <p className="font-medium text-amber-700">{t("import.skipped_found", { count: totalCount })}</p>
      {truncated && <p className="text-xs text-gray-500 dark:text-gray-400">{t("import.skipped_truncated", { shown: rows.length })}</p>}
      <div className="mt-1 max-h-64 overflow-auto">
        <table className="w-full text-xs">
          <thead className="text-left text-gray-500 dark:text-gray-400">
            <tr>
              <th className="py-1">{t("import.row")}</th>
              <th>{t("import.skipped_summary")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} className="border-t border-border">
                <td className="py-1">{r.row || "—"}</td>
                <td className="text-gray-700 dark:text-gray-200">{r.summary}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function ResultPanel({ result, onReset }: { result: ExecuteResult; onReset: () => void }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-2 rounded border border-emerald-300 bg-emerald-50 p-3 text-sm text-emerald-900 dark:bg-emerald-900/30 dark:text-emerald-100 dark:border-emerald-700">
      <p className="font-medium">{t("import.done")}</p>
      <p>
        {t("import.inserted")}: {result.inserted} · {t("import.skipped")}: {result.skipped}
        {result.replaced > 0 ? ` · ${t("import.replaced")}: ${result.replaced}` : ""}
      </p>
      {result.skippedRows.length > 0 && (
        <SkippedRowsTable rows={result.skippedRows} truncated={result.truncatedSkipped} totalCount={result.skipped} />
      )}
      <Button variant="secondary" onClick={onReset}>{t("import.import_another")}</Button>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <p className="text-xs text-gray-500 dark:text-gray-400">{label}</p>
      <p className="text-lg font-medium">{value}</p>
    </div>
  );
}

export { PreviewPanel, ResultPanel };
