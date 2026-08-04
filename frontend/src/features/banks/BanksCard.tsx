import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  useAspsps,
  useBankConfig,
  useBankConnections,
  useDeleteConnection,
  useStartLink,
  useSyncConnection,
  useUpdateConnection,
  type BankConnection,
  type ConnectionStatus,
  type StartLinkInput,
} from "@/api/banks";
import { apiErrorMessage } from "@/api/client";
import { formatDate } from "@/lib/dates";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { showWhitelistPhase } from "./whitelistInstructionsBus";

// Common SEPA / open-banking countries; the ASPSP catalogue itself comes from the provider.
const COUNTRIES = ["NL", "ES", "DE", "FR", "BE", "IT", "PT", "IE", "AT", "FI", "GB"];

function statusClass(status: ConnectionStatus): string {
  switch (status) {
    case "active":
      return "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/50 dark:text-emerald-200";
    // Expired and the two credential states are all "needs your attention, nothing is broken".
    case "expired":
    case "credentials_required":
    case "credentials_mismatch":
      return "bg-amber-100 text-amber-800 dark:bg-amber-900/50 dark:text-amber-200";
    default:
      return "bg-red-100 text-red-800 dark:bg-red-900/50 dark:text-red-200";
  }
}

/** A connection parked by the credential gate: syncing is impossible until an owner acts. */
function isCredentialState(status: ConnectionStatus): boolean {
  return status === "credentials_required" || status === "credentials_mismatch";
}

/** Linking is open to every member — each relative passes SCA at their own bank, so credentials are never
 *  shared; managing a row is gated by `connection.canManage`, mirroring the server check. Rendered when the
 *  household has credentials **or** still has connections, so the ones left behind stay visible with the
 *  status that says why. */
export function BanksCard({
  householdId,
  locale,
  canReviewInstructions = false,
}: {
  householdId: string;
  locale: string;
  /** Whether the setup instructions are on this page to jump back to — they live in the owner-only card. */
  canReviewInstructions?: boolean;
}) {
  const { t } = useTranslation();
  const { data: connections = [] } = useBankConnections(householdId);
  const { data: config } = useBankConfig(householdId);
  const startLink = useStartLink(householdId);

  // Distinct daily background-sync times in the viewer's own timezone, sorted by time of day.
  const syncTimes = useMemo(() => {
    const byMinute = new Map<number, string>();
    for (const iso of config?.nextSyncTimes ?? []) {
      const d = new Date(iso);
      const key = d.getHours() * 60 + d.getMinutes();
      if (!byMinute.has(key)) byMinute.set(key, d.toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" }));
    }
    return Array.from(byMinute.entries()).sort((a, b) => a[0] - b[0]).map((e) => e[1]);
  }, [config?.nextSyncTimes, locale]);

  const canLink = config?.credentialsConfigured ?? false;
  const [country, setCountry] = useState("NL");
  const [aspspName, setAspspName] = useState("");
  const [label, setLabel] = useState("");
  const [linkError, setLinkError] = useState<string | null>(null);
  // Without credentials the catalogue call would only ever return BANK_CREDENTIALS_REQUIRED.
  const { data: aspsps = [], isLoading: aspspsLoading } = useAspsps(householdId, country, canLink);

  // Linking before whitelisting the account in the Control Panel is invisible afterwards — the connection
  // shows active and syncs nothing — and the written instructions haven't stopped it, so the household's
  // very first link asks once. Any existing connection means this household has been through it already;
  // acknowledging holds for the rest of the visit, so repeat links and re-links never see the dialog.
  const firstLinkForHousehold = (config?.connectionCount ?? 0) === 0;
  const [whitelistAcked, setWhitelistAcked] = useState(false);
  const [checkpointLink, setCheckpointLink] = useState<StartLinkInput | null>(null);

  const runLink = async (input: StartLinkInput) => {
    setLinkError(null);
    try {
      const res = await startLink.mutateAsync(input);
      window.location.href = res.authUrl;
    } catch (err) {
      setLinkError(apiErrorMessage(err, t));
    }
  };

  const beginLink = (relinkConnectionId?: string, presetAspsp?: string, presetCountry?: string) => {
    setLinkError(null);
    const name = presetAspsp ?? aspspName;
    if (!name) {
      setLinkError(t("banks.pick_bank"));
      return;
    }
    const input: StartLinkInput = {
      aspspName: name,
      country: presetCountry ?? country,
      label: label || undefined,
      relinkConnectionId,
    };
    if (firstLinkForHousehold && !whitelistAcked) {
      setCheckpointLink(input);
      return;
    }
    void runLink(input);
  };

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("banks.title")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("banks.settings_description")}</p>
      </CardHeader>
      <CardBody className="space-y-4">
        <div className="rounded-md border border-sky-200 bg-sky-50 p-3 text-sm text-gray-700 dark:border-sky-900 dark:bg-sky-950/30 dark:text-gray-300">
          <p className="font-medium">{t("banks.explain_title")}</p>
          <ul className="mt-1 list-disc space-y-0.5 pl-5">
            <li>{t("banks.explain_sca")}</li>
            <li>{t("banks.explain_readonly")}</li>
            <li>{t("banks.explain_expiry")}</li>
            <li>{t("banks.explain_selfhosted")}</li>
          </ul>
        </div>

        {canLink && syncTimes.length > 0 && (
          <p className="text-sm text-gray-500 dark:text-gray-400">
            {t("banks.auto_sync_schedule", { times: syncTimes.join(", ") })}
          </p>
        )}

        {!canLink && (
          <div className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-200">
            {t("banks.credentials_needed_notice")}
          </div>
        )}

        {canLink && (
          <>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
              <div>
                <Label>{t("banks.country")}</Label>
                <Select value={country} onChange={(e) => { setCountry(e.target.value); setAspspName(""); }}>
                  {COUNTRIES.map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </Select>
              </div>
              <div>
                <Label>{t("banks.bank")}</Label>
                <Select value={aspspName} onChange={(e) => setAspspName(e.target.value)} disabled={aspspsLoading}>
                  <option value="">{aspspsLoading ? t("common.loading") : t("banks.pick_bank")}</option>
                  {aspsps.map((a) => (
                    <option key={`${a.name}-${a.country}`} value={a.name}>{a.name}</option>
                  ))}
                </Select>
              </div>
              <div>
                <Label>{t("banks.label")}</Label>
                <Input value={label} onChange={(e) => setLabel(e.target.value)} placeholder={t("banks.label_placeholder")} />
              </div>
              <div className="flex items-end">
                <Button disabled={startLink.isPending || !aspspName} onClick={() => beginLink()}>
                  {t("banks.link_bank")}
                </Button>
              </div>
            </div>
            {linkError && <FieldError message={linkError} />}
          </>
        )}

        {connections.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("banks.no_connections")}</p>
        ) : (
          <ul className="space-y-2">
            {connections.map((c) => (
              <ConnectionRow
                key={c.id}
                householdId={householdId}
                connection={c}
                locale={locale}
                canLink={canLink}
                onRelink={() => beginLink(c.id, c.aspspName, c.aspspCountry)}
              />
            ))}
          </ul>
        )}
      </CardBody>

      {checkpointLink && (
        <WhitelistCheckpointDialog
          canReviewInstructions={canReviewInstructions}
          onContinue={() => {
            setWhitelistAcked(true);
            const input = checkpointLink;
            setCheckpointLink(null);
            void runLink(input);
          }}
          onReview={() => {
            setCheckpointLink(null);
            showWhitelistPhase();
          }}
          onClose={() => setCheckpointLink(null)}
        />
      )}
    </Card>
  );
}

/** One-time-per-household reminder shown between "Link bank" and the redirect to the bank. */
function WhitelistCheckpointDialog({
  canReviewInstructions,
  onContinue,
  onReview,
  onClose,
}: {
  canReviewInstructions: boolean;
  onContinue: () => void;
  onReview: () => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-black/40 px-4 py-8"
      role="dialog"
      aria-modal="true"
      onClick={onClose}
    >
      <Card className="w-full max-w-md" onClick={(e) => e.stopPropagation()}>
        <CardHeader>
          <p className="font-medium">{t("banks.whitelist_check_title")}</p>
        </CardHeader>
        <CardBody className="space-y-3">
          <p className="text-sm text-gray-600 dark:text-gray-300">{t("banks.whitelist_check_body")}</p>
          <div className="flex flex-wrap justify-end gap-2">
            {canReviewInstructions && (
              <Button variant="secondary" onClick={onReview}>{t("banks.whitelist_check_review")}</Button>
            )}
            <Button onClick={onContinue}>{t("banks.whitelist_check_continue")}</Button>
          </div>
        </CardBody>
      </Card>
    </div>
  );
}

function ConnectionRow({
  householdId,
  connection,
  locale,
  canLink,
  onRelink,
}: {
  householdId: string;
  connection: BankConnection;
  locale: string;
  /** False while the household has no Enable Banking credentials — nothing can reach the provider. */
  canLink: boolean;
  onRelink: () => void;
}) {
  const { t } = useTranslation();
  const sync = useSyncConnection(householdId);
  const update = useUpdateConnection(householdId);
  const remove = useDeleteConnection(householdId);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const expires = connection.consentExpiresAt
    ? formatDate(connection.consentExpiresAt, locale)
    : "—";
  const lastSynced = connection.lastSyncedAt
    ? formatDate(connection.lastSyncedAt, locale, "PPp")
    : t("banks.never_synced");

  return (
    <li className="rounded-md border border-border p-3 dark:border-gray-700">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0 break-words">
          <span className="font-medium">{connection.label ?? connection.aspspName}</span>
          <span className="ml-2 text-xs text-gray-500 dark:text-gray-400">{connection.aspspName} · {connection.aspspCountry}</span>
          <span className={`ml-2 rounded px-1.5 py-0.5 text-xs ${statusClass(connection.status)}`}>
            {t(`banks.status_${connection.status}`)}
          </span>
        </div>
        {connection.canManage && (
          <div className="flex flex-wrap gap-1">
            {/* Everything but Delete needs the provider, so it needs credentials first. */}
            <Button variant="ghost" disabled={sync.isPending || !canLink} onClick={() => sync.mutate(connection.id)}>
              {t("banks.sync_now")}
            </Button>
            {connection.status !== "active" && (
              <Button variant="ghost" disabled={!canLink} onClick={onRelink}>{t("banks.relink")}</Button>
            )}
            <Button
              variant="ghost"
              onClick={() => update.mutate({ id: connection.id, input: { ingestionEnabled: !connection.ingestionEnabled } })}
            >
              {connection.ingestionEnabled ? t("banks.disable_ingestion") : t("banks.enable_ingestion")}
            </Button>
            <Button variant="ghost" className="text-red-600 dark:text-red-400" onClick={() => setConfirmDelete((v) => !v)}>
              {t("common.delete")}
            </Button>
          </div>
        )}
      </div>
      {isCredentialState(connection.status) && (
        <p className="mt-1 text-xs text-amber-700 dark:text-amber-400">
          {connection.status === "credentials_required"
            ? t("banks.status_credentials_required_hint")
            : t("banks.status_credentials_mismatch_hint")}
        </p>
      )}
      <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
        {t("banks.expires")}: {expires} · {t("banks.last_synced")}: {lastSynced}
        {connection.accounts.length > 0 && ` · ${connection.accounts.map((a) => a.name ?? a.ibanMasked ?? "").filter(Boolean).join(", ")}`}
      </p>
      {connection.lastSyncStatus === "error" && connection.lastSyncError && (
        <p className="mt-1 text-xs text-red-600">{connection.lastSyncError}</p>
      )}
      {confirmDelete && (
        <div className="mt-3 flex items-center gap-2 rounded-md border border-red-300 bg-red-50 p-3 dark:border-red-800 dark:bg-red-950/30">
          <p className="flex-1 text-sm text-red-800 dark:text-red-300">{t("banks.delete_confirm")}</p>
          <Button variant="danger" disabled={remove.isPending} onClick={() => remove.mutate(connection.id)}>
            {t("common.delete")}
          </Button>
          <Button variant="secondary" onClick={() => setConfirmDelete(false)}>{t("common.cancel")}</Button>
        </div>
      )}
    </li>
  );
}
