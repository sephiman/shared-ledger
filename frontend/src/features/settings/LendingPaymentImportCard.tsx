import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { asApiError } from "@/api/client";
import { useExecuteLendingPayments, usePreviewLendingPayments, type ExecuteResult, type PreviewSummary } from "@/api/import";
import { Button, Card, CardBody, CardHeader, FieldError } from "@/components/ui/primitives";
import { CsvFormatHelp } from "./CsvFormatHelp";
import { FilePicker } from "./FilePicker";
import { PreviewPanel, ResultPanel } from "./TransactionImportCard";

export function LendingPaymentImportCard() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<PreviewSummary | null>(null);
  const [result, setResult] = useState<ExecuteResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const previewMut = usePreviewLendingPayments(household.householdId);
  const executeMut = useExecuteLendingPayments(household.householdId);

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
      setPreview(await previewMut.mutateAsync(file));
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
      setResult(await executeMut.mutateAsync(file));
      setPreview(null);
    } catch (err) {
      const api = asApiError(err);
      setError(t(`errors.${api.code}`, api.message));
    }
  }

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("import.lending_payments")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("import.lending_payments_description")}</p>
        <CsvFormatHelp dataset="lending_payments" />
      </CardHeader>
      <CardBody className="space-y-3">
        {!result && (
          <>
            <FilePicker file={file} onChange={(f) => { setFile(f); setPreview(null); setError(null); }} />
            {!preview && (
              <Button onClick={runPreview} disabled={!file || previewMut.isPending}>
                {previewMut.isPending ? t("common.loading") : t("import.validate")}
              </Button>
            )}
          </>
        )}
        <FieldError message={error} />
        {preview && !result && <PreviewPanel preview={preview} dataset="lending_payments" />}
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
