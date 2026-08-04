import { useEffect, useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import {
  useBankCredentials,
  useSaveBankCredentials,
  useValidateBankCredentials,
} from "@/api/banks";
import { asApiError } from "@/api/client";
import { Button, Card, CardBody, CardHeader, Input, Label, Textarea } from "@/components/ui/primitives";
import { FilePicker } from "@/features/settings/FilePicker";
import {
  MAX_KEY_FILE_BYTES,
  keyFileSizeError,
  keyFileTextError,
  looksLikePkcs1,
  normalizeKey,
} from "./keyFile";
import { onShowWhitelistPhase } from "./whitelistInstructionsBus";

/** Owner-only Enable Banking application for this household — always visible, so the feature can be set up
 *  from scratch. The private key is write-only. */
export function BankCredentialsCard({ householdId }: { householdId: string }) {
  const { t } = useTranslation();
  const { data: credentials } = useBankCredentials(householdId);
  const save = useSaveBankCredentials(householdId);
  const validate = useValidateBankCredentials(householdId);

  const [appId, setAppId] = useState("");
  const [privateKey, setPrivateKey] = useState("");
  // Held only for its name and the Clear button; the text lives in privateKey like a paste.
  const [keyFile, setKeyFile] = useState<File | null>(null);
  const [keyError, setKeyError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [pkcs1Warning, setPkcs1Warning] = useState(false);
  const [saveMsg, setSaveMsg] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  // Set when the server refuses a save that would strand connections; holds the count to show.
  const [relinkWarning, setRelinkWarning] = useState<number | null>(null);
  const [testMsg, setTestMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const phaseBRef = useRef<HTMLElement>(null);
  // Bumped by the Banks card's pre-link checkpoint. A counter rather than a boolean, so a second
  // "Review instructions" with the panel already open scrolls again.
  const [phaseBRequests, setPhaseBRequests] = useState(0);

  useEffect(() => {
    if (credentials) setAppId(credentials.appId ?? "");
  }, [credentials]);

  useEffect(() => onShowWhitelistPhase(() => {
    setHelpOpen(true);
    setPhaseBRequests((n) => n + 1);
  }), []);

  // Both updates above land in one render, so Phase B exists by the time this runs.
  useEffect(() => {
    if (phaseBRequests === 0) return;
    phaseBRef.current?.scrollIntoView?.({ behavior: "smooth", block: "start" });
  }, [phaseBRequests]);

  const onKeyChange = (value: string) => {
    setPkcs1Warning(looksLikePkcs1(value));
    setPrivateKey(value);
  };

  /**
   * Both entry points end at [onKeyChange]: an uploaded file is just another way of typing the key,
   * so normalization, the PKCS#1 hint and the request payload are identical either way.
   */
  const loadKeyFile = async (file: File | null) => {
    setKeyError(null);
    if (!file) {
      setKeyFile(null);
      onKeyChange("");
      return;
    }
    if (keyFileSizeError(file.size)) {
      setKeyFile(null);
      setKeyError(t("banks.credentials_key_file_too_large", { max: Math.round(MAX_KEY_FILE_BYTES / 1024) }));
      return;
    }
    const text = await file.text();
    const problem = keyFileTextError(text);
    if (problem) {
      setKeyFile(null);
      setKeyError(t(problem === "binary" ? "banks.credentials_key_file_binary" : "banks.credentials_key_file_empty"));
      return;
    }
    setKeyFile(file);
    onKeyChange(text);
  };

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragging(false);
    void loadKeyFile(e.dataTransfer.files?.[0] ?? null);
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
      setKeyFile(null);
      setKeyError(null);
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
          {keyFile ? (
            // A loaded file replaces the textarea: dumping ~1.7 KB of base64 into it helps nobody.
            <div className="rounded-md border border-emerald-300 bg-emerald-50 px-3 py-2 text-sm text-emerald-800 dark:border-emerald-800 dark:bg-emerald-950/30 dark:text-emerald-200">
              {t("banks.credentials_key_file_loaded", { name: keyFile.name })}
            </div>
          ) : (
            <div
              onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
              onDragLeave={() => setDragging(false)}
              onDrop={onDrop}
              className={dragging ? "rounded-md ring-2 ring-primary" : undefined}
            >
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
            </div>
          )}
          <div className="mt-2">
            <FilePicker
              file={keyFile}
              onChange={(f) => void loadKeyFile(f)}
              accept=".pem,.key,.p8,.txt"
              chooseLabel={t("banks.credentials_upload_key")}
              changeLabel={t("banks.credentials_change_key_file")}
              emptyLabel={t("banks.credentials_no_key_file")}
            />
          </div>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("banks.credentials_key_hint")}</p>
          {!keyFile && (
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("banks.credentials_key_drop_hint")}</p>
          )}
          {pkcs1Warning && (
            <p className="mt-1 text-xs text-amber-700 dark:text-amber-400">{t("banks.credentials_key_pkcs1")}</p>
          )}
          {keyError && <p className="mt-1 text-xs text-red-600 dark:text-red-400">{keyError}</p>}
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
            // Three phases in a fixed order, because doing C before B fails silently: the connection looks
            // active and syncs nothing. Hence the gate at the end of B and the checkpoint in the Banks card.
            <div className="mt-2 space-y-4 rounded-md border border-border bg-gray-50 p-3 text-sm dark:border-gray-700 dark:bg-gray-900/40">
              <PhaseSection letter="A" title={t("banks.credentials_help_phase_a_title")} note={t("banks.credentials_help_phase_a_note")}>
                <ol className="list-decimal space-y-1.5 pl-5 text-gray-600 dark:text-gray-300">
                  <li>
                    {t("banks.credentials_help_step1")}{" "}
                    <a className="text-primary" href="https://enablebanking.com" target="_blank" rel="noreferrer">
                      enablebanking.com
                    </a>
                  </li>
                  <li>{t("banks.credentials_help_step2")}</li>
                  <li>{t("banks.credentials_help_step3")}</li>
                  <li>{t("banks.credentials_help_step4")}</li>
                  <li>
                    {t("banks.credentials_help_step5")}{" "}
                    <code className="break-all rounded bg-gray-100 px-1 py-0.5 text-xs dark:bg-gray-900/60">
                      {credentials?.redirectUrl ?? ""}
                    </code>
                  </li>
                  <li>{t("banks.credentials_help_step6")}</li>
                  <li>{t("banks.credentials_help_step7")}</li>
                  <li>
                    {t("banks.credentials_help_step8_save")}{" "}
                    <strong className="font-semibold text-gray-700 dark:text-gray-200">
                      {t("banks.credentials_help_step8_gate")}
                    </strong>
                  </li>
                </ol>
              </PhaseSection>

              <PhaseSection
                letter="B"
                title={t("banks.credentials_help_phase_b_title")}
                note={t("banks.credentials_help_phase_b_note")}
                sectionRef={phaseBRef}
                className="border-t border-border pt-4 dark:border-gray-700"
              >
                {/* The warning that used to trail the whole list, now sitting where the action happens. */}
                <div className="rounded-md border border-amber-300 bg-amber-50 p-2 dark:border-amber-800 dark:bg-amber-950/30">
                  <p className="font-medium text-amber-800 dark:text-amber-200">
                    {t("banks.credentials_help_whitelist_title")}
                  </p>
                  <p className="mt-1 text-amber-800 dark:text-amber-200">
                    {t("banks.credentials_help_whitelist_body")}
                  </p>
                </div>
                <ol className="list-decimal space-y-1.5 pl-5 text-gray-600 dark:text-gray-300">
                  <li>{t("banks.credentials_help_b_step1")}</li>
                  <li>{t("banks.credentials_help_b_step2")}</li>
                  <li>{t("banks.credentials_help_b_step3")}</li>
                  <li>{t("banks.credentials_help_b_step4")}</li>
                </ol>
                <div className="rounded-md border-2 border-amber-500 bg-amber-100 p-2 dark:border-amber-600 dark:bg-amber-900/40">
                  <p className="font-semibold uppercase tracking-wide text-amber-900 dark:text-amber-200">
                    {t("banks.credentials_help_b_gate_label")}
                  </p>
                  <p className="mt-1 font-medium text-amber-900 dark:text-amber-100">
                    {t("banks.credentials_help_b_gate")}
                  </p>
                </div>
              </PhaseSection>

              <PhaseSection
                letter="C"
                title={t("banks.credentials_help_phase_c_title")}
                className="border-t border-border pt-4 dark:border-gray-700"
              >
                <ul className="list-disc space-y-1.5 pl-5 text-gray-600 dark:text-gray-300">
                  <li>{t("banks.credentials_help_c_step1")}</li>
                  <li>{t("banks.credentials_help_c_step2")}</li>
                </ul>
                <p className="rounded-md border border-border bg-white p-2 text-gray-600 dark:border-gray-700 dark:bg-gray-900/60 dark:text-gray-300">
                  {t("banks.credentials_help_c_symptom")}
                </p>
              </PhaseSection>

              <div className="space-y-1 border-t border-border pt-4 dark:border-gray-700">
                <p className="text-gray-600 dark:text-gray-300">{t("banks.credentials_help_identity")}</p>
                <p className="text-xs text-gray-500 dark:text-gray-400">{t("banks.credentials_help_encrypted")}</p>
              </div>
            </div>
          )}
        </div>
      </CardBody>
    </Card>
  );
}

/** One numbered phase of the setup instructions: lettered badge, bold title, steps indented under it. */
function PhaseSection({
  letter,
  title,
  note,
  sectionRef,
  className,
  children,
}: {
  letter: string;
  title: string;
  /** When the phase happens ("do this first, once") — the ordering hint, kept out of the title. */
  note?: string;
  sectionRef?: React.Ref<HTMLElement>;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section ref={sectionRef} className={className}>
      <div className="flex items-start gap-2">
        <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">
          {letter}
        </span>
        <div className="min-w-0">
          <p className="font-semibold text-gray-900 dark:text-gray-100">{title}</p>
          {note && <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{note}</p>}
        </div>
      </div>
      <div className="mt-2 space-y-2 sm:pl-8">{children}</div>
    </section>
  );
}
