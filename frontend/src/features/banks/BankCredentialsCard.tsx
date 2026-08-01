import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  useBankCredentials,
  useSaveBankCredentials,
  useValidateBankCredentials,
} from "@/api/banks";
import { asApiError } from "@/api/client";
import { Button, Card, CardBody, CardHeader, Input, Label, Textarea } from "@/components/ui/primitives";

/** Strips PEM armour and whitespace so a key file and a one-line blob save identically. The server
 *  normalizes again and has the final say. */
function normalizeKey(raw: string): string {
  return raw
    .replace(/\\n/g, "\n")
    .replace(/-----(BEGIN|END)[^-]*-----/g, "")
    .replace(/\s/g, "");
}

/** PKCS#1 is the common wrong paste; flag it while typing rather than on save. */
function looksLikePkcs1(raw: string): boolean {
  return /BEGIN\s+RSA\s+PRIVATE\s+KEY/i.test(raw);
}

/**
 * Owner-only Enable Banking application for this household — always visible, so the feature can be
 * set up from scratch. As with the Telegram card, the private key is write-only.
 */
export function BankCredentialsCard({ householdId }: { householdId: string }) {
  const { t } = useTranslation();
  const { data: credentials } = useBankCredentials(householdId);
  const save = useSaveBankCredentials(householdId);
  const validate = useValidateBankCredentials(householdId);

  const [appId, setAppId] = useState("");
  const [privateKey, setPrivateKey] = useState("");
  const [pkcs1Warning, setPkcs1Warning] = useState(false);
  const [saveMsg, setSaveMsg] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  // Set when the server refuses a save that would strand connections; holds the count to show.
  const [relinkWarning, setRelinkWarning] = useState<number | null>(null);
  const [testMsg, setTestMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);

  useEffect(() => {
    if (credentials) setAppId(credentials.appId ?? "");
  }, [credentials]);

  const onKeyChange = (value: string) => {
    setPkcs1Warning(looksLikePkcs1(value));
    setPrivateKey(value);
  };

  const runValidation = async () => {
    setTestMsg(null);
    try {
      const result = await validate.mutateAsync();
      setTestMsg({
        ok: result.ok,
        text: result.ok ? t("banks.credentials_valid") : (result.message ?? t("banks.credentials_invalid")),
      });
    } catch (err) {
      const api = asApiError(err);
      setTestMsg({ ok: false, text: t(`errors.${api.code}`, api.message) });
    }
  };

  const onSave = async (confirm = false) => {
    setSaveMsg(null);
    setSaveError(null);
    setTestMsg(null);
    if (!appId.trim()) {
      setSaveError(t("errors.field_required"));
      return;
    }
    try {
      await save.mutateAsync({
        appId: appId.trim(),
        privateKey: normalizeKey(privateKey) || undefined,
        confirm,
      });
      setPrivateKey("");
      setPkcs1Warning(false);
      setRelinkWarning(null);
      setSaveMsg(t("common.saved"));
      // Surface a bad app id / key mismatch now rather than at the first sync.
      await runValidation();
    } catch (err) {
      const api = asApiError(err);
      if (api.code === "BANK_CREDENTIALS_APP_ID_CHANGED") {
        // From the server: on a first save there is no stored app id to compare the typed one to.
        setRelinkWarning(Number(api.fields?.connections ?? credentials?.mismatchedConnectionCount ?? 0));
        return;
      }
      setSaveError(t(`errors.${api.code}`, api.message));
    }
  };

  const copyRedirect = async () => {
    if (!credentials?.redirectUrl) return;
    await navigator.clipboard.writeText(credentials.redirectUrl);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("banks.credentials_title")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("banks.credentials_description")}</p>
      </CardHeader>
      <CardBody className="space-y-4">
        <div>
          <Label>{t("banks.credentials_app_id")}</Label>
          <Input
            value={appId}
            onChange={(e) => setAppId(e.target.value)}
            autoComplete="off"
            placeholder="00000000-0000-0000-0000-000000000000"
          />
        </div>

        <div>
          <Label>{t("banks.credentials_private_key")}</Label>
          <Textarea
            rows={3}
            autoComplete="off"
            spellCheck={false}
            value={privateKey}
            onChange={(e) => onKeyChange(e.target.value)}
            placeholder={
              credentials?.privateKeyConfigured
                ? t("banks.credentials_key_configured")
                : t("banks.credentials_key_not_configured")
            }
          />
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("banks.credentials_key_hint")}</p>
          {pkcs1Warning && (
            <p className="mt-1 text-xs text-amber-700 dark:text-amber-400">{t("banks.credentials_key_pkcs1")}</p>
          )}
        </div>

        {credentials?.redirectUrl && (
          <div className="rounded-md border border-border p-3 dark:border-gray-700">
            <p className="text-sm font-medium">{t("banks.credentials_redirect_title")}</p>
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("banks.credentials_redirect_hint")}</p>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <code className="min-w-0 break-all rounded bg-gray-100 px-2 py-1 text-xs dark:bg-gray-900/60">
                {credentials.redirectUrl}
              </code>
              <Button variant="secondary" onClick={copyRedirect}>
                {copied ? t("common.copied") : t("common.copy")}
              </Button>
            </div>
          </div>
        )}

        {relinkWarning !== null && (
          <div className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm dark:border-amber-800 dark:bg-amber-950/30">
            <p className="text-amber-800 dark:text-amber-200">
              {t("banks.credentials_relink_warning", { count: relinkWarning })}
            </p>
            <div className="mt-2 flex gap-2">
              <Button variant="danger" disabled={save.isPending} onClick={() => onSave(true)}>
                {t("banks.credentials_save_anyway")}
              </Button>
              <Button variant="secondary" onClick={() => setRelinkWarning(null)}>{t("common.cancel")}</Button>
            </div>
          </div>
        )}

        <div className="flex flex-wrap items-center gap-3">
          <Button onClick={() => onSave()} disabled={save.isPending}>{t("common.save")}</Button>
          <Button
            variant="secondary"
            onClick={runValidation}
            disabled={validate.isPending || !credentials?.privateKeyConfigured}
          >
            {validate.isPending ? t("banks.credentials_validating") : t("banks.credentials_validate")}
          </Button>
          {saveMsg && <span className="text-sm text-gray-600 dark:text-gray-300">{saveMsg}</span>}
        </div>
        {saveError && (
          <div className="rounded-md border border-red-300 bg-red-50 p-2 text-sm text-red-800 dark:border-red-800 dark:bg-red-950/30 dark:text-red-300">
            {saveError}
          </div>
        )}
        {testMsg && (
          <div
            className={
              testMsg.ok
                ? "rounded-md border border-emerald-300 bg-emerald-50 p-2 text-sm text-emerald-800 dark:border-emerald-800 dark:bg-emerald-950/30 dark:text-emerald-200"
                : "rounded-md border border-red-300 bg-red-50 p-2 text-sm text-red-800 dark:border-red-800 dark:bg-red-950/30 dark:text-red-300"
            }
          >
            {testMsg.text}
          </div>
        )}

        <div>
          <button type="button" onClick={() => setHelpOpen((o) => !o)} className="text-sm text-primary">
            {helpOpen ? t("banks.credentials_help_hide") : t("banks.credentials_help_show")}
          </button>
          {helpOpen && (
            <div className="mt-2 space-y-3 rounded-md border border-border bg-gray-50 p-3 text-sm dark:border-gray-700 dark:bg-gray-900/40">
              <div>
                <p className="font-medium">{t("banks.credentials_help_step1_title")}</p>
                <p className="mt-1 text-gray-600 dark:text-gray-300">{t("banks.credentials_help_step1_body")}</p>
                <a className="text-primary" href="https://enablebanking.com" target="_blank" rel="noreferrer">
                  https://enablebanking.com
                </a>
              </div>
              <div>
                <p className="font-medium">{t("banks.credentials_help_step2_title")}</p>
                <p className="mt-1 text-gray-600 dark:text-gray-300">{t("banks.credentials_help_step2_body")}</p>
              </div>
              <div>
                <p className="font-medium">{t("banks.credentials_help_step3_title")}</p>
                <p className="mt-1 text-gray-600 dark:text-gray-300">
                  {t("banks.credentials_help_step3_body", { url: credentials?.redirectUrl ?? "" })}
                </p>
              </div>
              <div className="rounded-md border border-amber-300 bg-amber-50 p-2 dark:border-amber-800 dark:bg-amber-950/30">
                <p className="font-medium text-amber-800 dark:text-amber-200">
                  {t("banks.credentials_help_whitelist_title")}
                </p>
                <p className="mt-1 text-amber-800 dark:text-amber-200">
                  {t("banks.credentials_help_whitelist_body")}
                </p>
              </div>
              <p className="text-gray-600 dark:text-gray-300">{t("banks.credentials_help_identity")}</p>
              <p className="text-xs text-gray-500 dark:text-gray-400">{t("banks.credentials_help_encrypted")}</p>
            </div>
          )}
        </div>
      </CardBody>
    </Card>
  );
}
