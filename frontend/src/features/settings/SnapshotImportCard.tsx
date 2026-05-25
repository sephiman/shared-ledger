import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { asApiError } from "@/api/client";
import { useExecuteSnapshots, usePreviewSnapshots, type ExecuteResult, type PreviewSummary, type SnapshotPolicy } from "@/api/import";
import { Button, Card, CardBody, CardHeader, FieldError, Label, Select } from "@/components/ui/primitives";
import { PreviewPanel, ResultPanel } from "./TransactionImportCard";
import { CsvFormatHelp } from "./CsvFormatHelp";
import { FilePicker } from "./FilePicker";

export function SnapshotImportCard() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const [file, setFile] = useState<File | null>(null);
  const [policy, setPolicy] = useState<SnapshotPolicy>("skip");
  const [preview, setPreview] = useState<PreviewSummary | null>(null);
  const [result, setResult] = useState<ExecuteResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const previewMut = usePreviewSnapshots(household.householdId);
  const executeMut = useExecuteSnapshots(household.householdId);

  function reset() {
    setFile(null);
    setPreview(null);
    setResult(null);
    setError(null);
    setPolicy("skip");
  }

  async function runPreview() {
    if (!file) return;
    setError(null);
    setResult(null);
    try {
      const p = await previewMut.mutateAsync({ file, policy });
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
      const r = await executeMut.mutateAsync({ file, policy });
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
        <p className="font-medium">{t("import.snapshots")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("import.snapshots_description")}</p>
        <CsvFormatHelp dataset="snapshots" />
      </CardHeader>
      <CardBody className="space-y-3">
        {!result && (
          <>
            <div className="space-y-3">
              <div>
                <Label>{t("import.file")}</Label>
                <FilePicker
                  file={file}
                  onChange={(f) => { setFile(f); setPreview(null); setError(null); }}
                />
              </div>
              <div>
                <Label>{t("import.duplicate_policy")}</Label>
                <Select
                  value={policy}
                  onChange={(e) => { setPolicy(e.target.value as SnapshotPolicy); setPreview(null); }}
                >
                  <option value="skip">{t("import.policy_skip")}</option>
                  <option value="replace">{t("import.policy_replace")}</option>
                  <option value="abort">{t("import.policy_abort")}</option>
                </Select>
              </div>
            </div>
            {!preview && (
              <Button onClick={runPreview} disabled={!file || previewMut.isPending}>
                {previewMut.isPending ? t("common.loading") : t("import.validate")}
              </Button>
            )}
          </>
        )}

        <FieldError message={error} />

        {preview && !result && <PreviewPanel preview={preview} dataset="snapshots" />}

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
