import { useEffect, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Button, Card, CardBody, CardHeader, Input, Label, Toggle } from "@/components/ui/primitives";
import { apiErrorMessage, asApiError } from "@/api/client";
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
  | "notifyRecurringLending"
  | "notifyBankMovements"
  | "notifyBankConnections";

const ENTITY_TOGGLES: { key: EntityToggleKey; labelKey: string }[] = [
  { key: "notifyTransactions", labelKey: "notifications.entity_transactions" },
  { key: "notifySnapshots", labelKey: "notifications.entity_snapshots" },
  { key: "notifyMovements", labelKey: "notifications.entity_movements" },
  { key: "notifyLendingPayments", labelKey: "notifications.entity_lending_payments" },
  { key: "notifyHoldings", labelKey: "notifications.entity_holdings" },
  { key: "notifyRecurringTxn", labelKey: "notifications.entity_recurring_txn" },
  { key: "notifyRecurringLending", labelKey: "notifications.entity_recurring_lending" },
  { key: "notifyBankMovements", labelKey: "notifications.entity_bank_movements" },
  { key: "notifyBankConnections", labelKey: "notifications.entity_bank_connections" },
];

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
    notifyBankMovements: true,
    notifyBankConnections: true,
    chatId: "",
  });
  const [token, setToken] = useState("");
  const [tokenConfigured, setTokenConfigured] = useState(false);
  const [saveMsg, setSaveMsg] = useState<string | null>(null);
  const [testMsg, setTestMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [helpOpen, setHelpOpen] = useState(false);
  const [copiedUrl, setCopiedUrl] = useState(false);

  const savedChatId = settings?.chatId ?? "";
  const canTest = tokenConfigured && savedChatId.trim().length > 0;
  // The test endpoint reads persisted settings, so testing with unsaved edits would silently hit old targets or 400.
  const hasUnsavedTarget = token.trim().length > 0 || (form.chatId ?? "").trim() !== savedChatId.trim();

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
        notifyBankMovements: settings.notifyBankMovements,
        notifyBankConnections: settings.notifyBankConnections,
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
      setSaveMsg(apiErrorMessage(err, t));
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
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("notifications.chat_id_hint")}</p>
          </div>
        </div>

        <div className="rounded-md border border-border p-3">
          <Toggle
            checked={form.active}
            onChange={(v) => set("active", v)}
            label={form.active ? t("notifications.master_active") : t("notifications.master_paused")}
          />
          <p className="text-xs text-gray-500 dark:text-gray-400">{t("notifications.master_hint")}</p>
        </div>

        <div>
          <p className="mb-1 text-sm font-medium">{t("notifications.entities_title")}</p>
          <div className="rounded-md border border-border px-3 divide-y divide-border">
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
          <Button variant="secondary" onClick={onTest} disabled={test.isPending || !canTest || hasUnsavedTarget}>
            {test.isPending ? t("notifications.test_sending") : t("notifications.test_button")}
          </Button>
          {saveMsg && <span className="text-sm text-gray-600 dark:text-gray-300">{saveMsg}</span>}
        </div>
        {hasUnsavedTarget && (
          <p className="text-xs text-amber-700 dark:text-amber-400">{t("notifications.test_save_first")}</p>
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
          <button
            type="button"
            onClick={() => setHelpOpen((o) => !o)}
            className="text-sm text-primary"
          >
            {helpOpen ? t("notifications.help_hide") : t("notifications.help_show")}
          </button>
          {helpOpen && (
            <div className="mt-2 space-y-4 rounded-md border border-border bg-gray-50 p-3 text-sm dark:bg-sunken/40">
              <SetupStep number={1} title={t("notifications.help_step1_title")}>
                <p>
                  {t("notifications.help_step1_body")}{" "}
                  <a className="text-primary" href="https://t.me/BotFather" target="_blank" rel="noreferrer">
                    https://t.me/BotFather
                  </a>
                </p>
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  {t("notifications.help_step1_example_label")}{" "}
                  <code className="break-all rounded bg-gray-100 px-1 py-0.5 dark:bg-sunken/60">
                    123456789:AAExampleTokenStringFromBotFather
                  </code>
                </p>
                <p className="text-xs text-gray-500 dark:text-gray-400">{t("notifications.help_step1_note")}</p>
              </SetupStep>

              <SetupStep number={2} title={t("notifications.help_step2_title")} className="border-t border-border pt-4">
                <p>{t("notifications.help_step2_intro")}</p>
                <ul className="list-disc space-y-1.5 pl-5">
                  <li>
                    <strong className="font-semibold text-gray-700 dark:text-gray-200">
                      {t("notifications.help_step2_private_label")}
                    </strong>{" "}
                    {t("notifications.help_step2_private_body")}
                  </li>
                  <li>
                    <strong className="font-semibold text-gray-700 dark:text-gray-200">
                      {t("notifications.help_step2_group_label")}
                    </strong>{" "}
                    {t("notifications.help_step2_group_body")}
                  </li>
                  <li>
                    <strong className="font-semibold text-gray-700 dark:text-gray-200">
                      {t("notifications.help_step2_channel_label")}
                    </strong>{" "}
                    {t("notifications.help_step2_channel_body")}
                  </li>
                </ul>
              </SetupStep>

              <SetupStep number={3} title={t("notifications.help_step3_title")} className="border-t border-border pt-4">
                <p>{t("notifications.help_step3_body")}</p>
                <div className="flex flex-wrap items-center gap-2">
                  <code className="min-w-0 break-all rounded bg-gray-100 px-2 py-1 text-xs dark:bg-sunken/60">
                    https://api.telegram.org/bot&lt;token&gt;/getUpdates
                  </code>
                  {token.trim().length > 0 && (
                    <Button
                      variant="secondary"
                      className="px-2 py-0.5 text-xs"
                      onClick={async () => {
                        try {
                          await navigator.clipboard?.writeText(
                            `https://api.telegram.org/bot${token.trim()}/getUpdates`,
                          );
                          setCopiedUrl(true);
                          setTimeout(() => setCopiedUrl(false), 2000);
                        } catch {
                          // Clipboard API can reject in insecure contexts; ignore silently.
                        }
                      }}
                    >
                      {copiedUrl ? t("common.copied") : t("notifications.help_step3_copy_url")}
                    </Button>
                  )}
                </div>
                <div>
                  <p>{t("notifications.help_step3_paths_intro")}</p>
                  <ul className="mt-1 list-disc space-y-1 pl-5">
                    <li>
                      {t("notifications.help_step3_path_message_label")}{" "}
                      <code className="rounded bg-gray-100 px-1 py-0.5 text-xs dark:bg-sunken/60">
                        result[].message.chat.id
                      </code>
                    </li>
                    <li>
                      {t("notifications.help_step3_path_member_label")}{" "}
                      <code className="rounded bg-gray-100 px-1 py-0.5 text-xs dark:bg-sunken/60">
                        result[].my_chat_member.chat.id
                      </code>
                    </li>
                    <li>
                      {t("notifications.help_step3_path_channel_label")}{" "}
                      <code className="rounded bg-gray-100 px-1 py-0.5 text-xs dark:bg-sunken/60">
                        result[].channel_post.chat.id
                      </code>
                    </li>
                  </ul>
                </div>
                <p className="text-xs text-gray-500 dark:text-gray-400">{t("notifications.help_step3_negative")}</p>
                <p className="text-xs text-gray-500 dark:text-gray-400">{t("notifications.help_step3_empty")}</p>
              </SetupStep>

              <SetupStep number={4} title={t("notifications.help_step4_title")} className="border-t border-border pt-4">
                <p>{t("notifications.help_step4_body")}</p>
              </SetupStep>
            </div>
          )}
        </div>
      </CardBody>
    </Card>
  );
}

/** One numbered step in the Telegram setup guide: circular badge, title, and indented details. */
function SetupStep({
  number,
  title,
  className,
  children,
}: {
  number: number;
  title: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section className={className}>
      <div className="flex items-start gap-2">
        <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">
          {number}
        </span>
        <p className="mt-0.5 font-semibold text-gray-900 dark:text-gray-100">{title}</p>
      </div>
      <div className="mt-2 space-y-2 text-gray-600 dark:text-gray-300 sm:pl-8">{children}</div>
    </section>
  );
}
