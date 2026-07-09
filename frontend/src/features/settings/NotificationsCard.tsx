import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Card, CardBody, CardHeader, Input, Label } from "@/components/ui/primitives";
import { asApiError } from "@/api/client";
import {
  useTelegramSettings,
  useTestTelegram,
  useUpdateTelegramSettings,
  type TelegramSettingsUpdate,
} from "@/api/notifications";

type EntityToggleKey =
  | "notifyTransactions"
  | "notifySnapshots"
  | "notifyMovements"
  | "notifyLendingPayments"
  | "notifyHoldings"
  | "notifyRecurringTxn"
  | "notifyRecurringLending";

const ENTITY_TOGGLES: { key: EntityToggleKey; labelKey: string }[] = [
  { key: "notifyTransactions", labelKey: "notifications.entity_transactions" },
  { key: "notifySnapshots", labelKey: "notifications.entity_snapshots" },
  { key: "notifyMovements", labelKey: "notifications.entity_movements" },
  { key: "notifyLendingPayments", labelKey: "notifications.entity_lending_payments" },
  { key: "notifyHoldings", labelKey: "notifications.entity_holdings" },
  { key: "notifyRecurringTxn", labelKey: "notifications.entity_recurring_txn" },
  { key: "notifyRecurringLending", labelKey: "notifications.entity_recurring_lending" },
];

function Toggle({ checked, disabled, onChange, label }: { checked: boolean; disabled?: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <label className="flex items-center justify-between gap-3 py-1.5 text-sm">
      <span className={disabled ? "text-gray-400 dark:text-gray-500" : ""}>{label}</span>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        className="h-4 w-4 rounded border-border text-primary focus:ring-primary disabled:opacity-50"
      />
    </label>
  );
}

export function NotificationsCard({ householdId }: { householdId: string }) {
  const { t } = useTranslation();
  const { data: settings } = useTelegramSettings(householdId, true);
  const update = useUpdateTelegramSettings(householdId);
  const test = useTestTelegram(householdId);

  const [form, setForm] = useState<Omit<TelegramSettingsUpdate, "botToken">>({
    active: true,
    notifyTransactions: true,
    notifySnapshots: true,
    notifyMovements: true,
    notifyLendingPayments: true,
    notifyHoldings: true,
    notifyRecurringTxn: true,
    notifyRecurringLending: true,
    chatId: "",
  });
  const [token, setToken] = useState("");
  const [tokenConfigured, setTokenConfigured] = useState(false);
  const [saveMsg, setSaveMsg] = useState<string | null>(null);
  const [testMsg, setTestMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [helpOpen, setHelpOpen] = useState(false);

  useEffect(() => {
    if (settings) {
      setForm({
        active: settings.active,
        notifyTransactions: settings.notifyTransactions,
        notifySnapshots: settings.notifySnapshots,
        notifyMovements: settings.notifyMovements,
        notifyLendingPayments: settings.notifyLendingPayments,
        notifyHoldings: settings.notifyHoldings,
        notifyRecurringTxn: settings.notifyRecurringTxn,
        notifyRecurringLending: settings.notifyRecurringLending,
        chatId: settings.chatId ?? "",
      });
      setTokenConfigured(settings.tokenConfigured);
    }
  }, [settings]);

  const set = <K extends keyof typeof form>(key: K, value: (typeof form)[K]) =>
    setForm((f) => ({ ...f, [key]: value }));

  const onSave = async () => {
    setSaveMsg(null);
    setTestMsg(null);
    try {
      const body: TelegramSettingsUpdate = { ...form, chatId: form.chatId?.trim() || null };
      if (token.trim()) body.botToken = token.trim();
      await update.mutateAsync(body);
      setToken("");
      setSaveMsg(t("common.saved"));
    } catch (err) {
      const api = asApiError(err);
      setSaveMsg(t(`errors.${api.code}`, api.message));
    }
  };

  const onTest = async () => {
    setTestMsg(null);
    try {
      const result = await test.mutateAsync();
      setTestMsg({
        ok: result.ok,
        text: result.ok ? t("notifications.test_ok") : (result.description ?? t("notifications.test_failed")),
      });
    } catch (err) {
      const api = asApiError(err);
      setTestMsg({ ok: false, text: t(`errors.${api.code}`, api.message) });
    }
  };

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("notifications.title")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("notifications.description")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("notifications.language_hint")}</p>
      </CardHeader>
      <CardBody className="space-y-4">
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <div>
            <Label>{t("notifications.bot_token")}</Label>
            <Input
              type="password"
              autoComplete="off"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder={tokenConfigured ? t("notifications.bot_token_configured") : t("notifications.bot_token_not_configured")}
            />
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("notifications.bot_token_keep_hint")}</p>
          </div>
          <div>
            <Label>{t("notifications.chat_id")}</Label>
            <Input
              value={form.chatId ?? ""}
              onChange={(e) => set("chatId", e.target.value)}
              placeholder="-1001234567890"
            />
          </div>
        </div>

        <div className="rounded-md border border-border p-3 dark:border-gray-700">
          <Toggle
            checked={form.active}
            onChange={(v) => set("active", v)}
            label={form.active ? t("notifications.master_active") : t("notifications.master_paused")}
          />
          <p className="text-xs text-gray-500 dark:text-gray-400">{t("notifications.master_hint")}</p>
        </div>

        <div>
          <p className="mb-1 text-sm font-medium">{t("notifications.entities_title")}</p>
          <div className="rounded-md border border-border px-3 dark:border-gray-700 divide-y divide-border dark:divide-gray-700">
            {ENTITY_TOGGLES.map(({ key, labelKey }) => (
              <Toggle
                key={key}
                checked={form[key]}
                disabled={!form.active}
                onChange={(v) => set(key, v)}
                label={t(labelKey)}
              />
            ))}
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <Button onClick={onSave} disabled={update.isPending}>{t("common.save")}</Button>
          <Button variant="secondary" onClick={onTest} disabled={test.isPending}>
            {test.isPending ? t("notifications.test_sending") : t("notifications.test_button")}
          </Button>
          {saveMsg && <span className="text-sm text-gray-600 dark:text-gray-300">{saveMsg}</span>}
        </div>
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
          <button
            type="button"
            onClick={() => setHelpOpen((o) => !o)}
            className="text-sm text-primary"
          >
            {helpOpen ? t("notifications.help_hide") : t("notifications.help_show")}
          </button>
          {helpOpen && (
            <div className="mt-2 space-y-3 rounded-md border border-border bg-gray-50 p-3 text-sm dark:border-gray-700 dark:bg-gray-900/40">
              <div>
                <p className="font-medium">{t("notifications.help_step1_title")}</p>
                <p className="mt-1 text-gray-600 dark:text-gray-300">{t("notifications.help_step1_body")}</p>
                <a className="text-primary" href="https://t.me/BotFather" target="_blank" rel="noreferrer">https://t.me/BotFather</a>
                <p className="mt-1 text-xs text-gray-500 dark:text-gray-400"><code>123456789:AAExampleTokenStringFromBotFather</code></p>
              </div>
              <div>
                <p className="font-medium">{t("notifications.help_step2_title")}</p>
                <p className="mt-1 text-gray-600 dark:text-gray-300">{t("notifications.help_step2_body")}</p>
              </div>
              <div>
                <p className="font-medium">{t("notifications.help_step3_title")}</p>
                <p className="mt-1 text-gray-600 dark:text-gray-300">{t("notifications.help_step3_body")}</p>
                <p className="mt-1 text-xs text-gray-500 dark:text-gray-400"><code>https://api.telegram.org/bot&lt;token&gt;/getUpdates</code></p>
                <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("notifications.help_step3_hint")}</p>
              </div>
            </div>
          )}
        </div>
      </CardBody>
    </Card>
  );
}
