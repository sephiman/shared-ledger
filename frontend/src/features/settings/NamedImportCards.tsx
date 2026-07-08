import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { asApiError } from "@/api/client";
import {
  useExecuteAmortization,
  useExecuteAssets,
  useExecuteLiabilitiesCsv,
  usePreviewAmortization,
  usePreviewAssets,
  usePreviewLiabilitiesCsv,
  type ExecuteResult,
  type PreviewSummary,
} from "@/api/import";
import { Button, Card, CardBody, CardHeader, FieldError } from "@/components/ui/primitives";
import { CsvFormatHelp } from "./CsvFormatHelp";
import { FilePicker } from "./FilePicker";
import { PreviewPanel, ResultPanel } from "./TransactionImportCard";

type Dataset = "assets" | "liabilities" | "amortization";

function ImportCard({
  dataset,
  titleKey,
  descKey,
  exportSuffix,
  usePreview,
  useExecute,
}: {
  dataset: Dataset;
  titleKey: string;
  descKey: string;
  exportSuffix: string;
  usePreview: (householdId: string) => { mutateAsync: (f: File) => Promise<PreviewSummary>; isPending: boolean };
  useExecute: (householdId: string) => { mutateAsync: (f: File) => Promise<ExecuteResult>; isPending: boolean };
}) {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<PreviewSummary | null>(null);
  const [result, setResult] = useState<ExecuteResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const previewMut = usePreview(household.householdId);
  const executeMut = useExecute(household.householdId);

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
        <div className="flex items-start justify-between gap-3">
          <p className="font-medium">{t(titleKey)}</p>
          <a
            href={`/api/households/${household.householdId}/${exportSuffix}`}
            download
            className="shrink-0 text-sm text-primary hover:underline"
          >
            {t("common.export_csv")}
          </a>
        </div>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t(descKey)}</p>
        <CsvFormatHelp dataset={dataset} />
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
        {preview && !result && <PreviewPanel preview={preview} dataset={dataset} />}
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

export function AssetImportCard() {
  return <ImportCard dataset="assets" titleKey="import.assets" descKey="import.assets_description" exportSuffix="assets/export.csv" usePreview={usePreviewAssets} useExecute={useExecuteAssets} />;
}

export function NamedLiabilityImportCard() {
  return <ImportCard dataset="liabilities" titleKey="import.liabilities_named" descKey="import.liabilities_named_description" exportSuffix="liabilities/export.csv" usePreview={usePreviewLiabilitiesCsv} useExecute={useExecuteLiabilitiesCsv} />;
}

export function AmortizationImportCard() {
  return <ImportCard dataset="amortization" titleKey="import.amortization" descKey="import.amortization_description" exportSuffix="liabilities/amortization/export.csv" usePreview={usePreviewAmortization} useExecute={useExecuteAmortization} />;
}
