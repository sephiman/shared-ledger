import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useAssetClasses } from "@/api/catalog";
import {
  useCreateMovement,
  useDeleteMovement,
  useLiabilities,
  useMovements,
  useUpdateMovement,
  type Movement,
  type MovementInput,
} from "@/api/networth";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select, Textarea } from "@/components/ui/primitives";
import { formatDate, isoToday } from "@/lib/dates";
import { formatMoney } from "@/lib/money";

type PanelMode = { kind: "closed" } | { kind: "create" } | { kind: "edit"; m: Movement };

function defaultDraft(): MovementInput {
  return {
    movementDate: isoToday(),
    type: "contribution",
    assetClassCode: null,
    liabilityId: null,
    amount: "",
    description: null,
  };
}

export function MovementsTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: page } = useMovements(household.householdId, { size: 100 });
  const { data: assetClasses = [] } = useAssetClasses();
  const { data: liabilities = [] } = useLiabilities(household.householdId);
  const create = useCreateMovement(household.householdId);
  const update = useUpdateMovement(household.householdId);
  const del = useDeleteMovement(household.householdId);
  const [panel, setPanel] = useState<PanelMode>({ kind: "closed" });
  const panelRef = useRef<HTMLDivElement | null>(null);
  const [draft, setDraft] = useState<MovementInput>(defaultDraft);
  const [errors, setErrors] = useState<{ date?: string; target?: string; amount?: string }>({});

  useEffect(() => {
    if (panel.kind === "edit") {
      panelRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }, [panel]);

  function openCreate() {
    setPanel({ kind: "create" });
    setDraft(defaultDraft());
    setErrors({});
  }

  function openEdit(m: Movement) {
    setPanel({ kind: "edit", m });
    setDraft({
      movementDate: m.movementDate,
      type: m.type,
      assetClassCode: m.assetClassCode,
      liabilityId: m.liabilityId,
      amount: m.amount,
      description: m.description,
    });
    setErrors({});
  }

  function closeForm() {
    setPanel({ kind: "closed" });
    setErrors({});
  }

  async function save() {
    const next: typeof errors = {};
    if (!draft.movementDate) next.date = t("errors.field_required");
    if (draft.type === "debt_payment") {
      if (!draft.liabilityId) next.target = t("errors.select_required");
    } else if (!draft.assetClassCode) {
      next.target = t("errors.select_required");
    }
    const amount = Number(draft.amount);
    if (!draft.amount.trim()) next.amount = t("errors.field_required");
    else if (!Number.isFinite(amount) || amount <= 0) next.amount = t("errors.amount_positive");
    if (Object.keys(next).length > 0) {
      setErrors(next);
      return;
    }
    const normalized: MovementInput = { ...draft };
    if (draft.type === "debt_payment") normalized.assetClassCode = null;
    else normalized.liabilityId = null;
    if (panel.kind === "edit") {
      await update.mutateAsync({ id: panel.m.id, input: normalized });
    } else {
      await create.mutateAsync(normalized);
    }
    closeForm();
  }

  function targetLabel(m: {
    type: string;
    assetClassCode: string | null;
    liabilityId: string | null;
    liabilityName?: string | null;
  }) {
    if (m.assetClassCode) return t(`asset.${m.assetClassCode}`);
    if (m.liabilityId) {
      // Prefer the server-resolved name (covers soft-deleted liabilities); fall back to the active list.
      return m.liabilityName ?? liabilities.find((x) => x.id === m.liabilityId)?.name ?? m.liabilityId;
    }
    return "—";
  }

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("networth.movements_description")}</p>
        <div className="flex gap-2">
          <a
            href={`/api/households/${household.householdId}/movements/export.csv`}
            download
            className="inline-flex items-center justify-center rounded-md border border-border bg-white px-4 py-2 text-sm font-medium text-gray-900 hover:bg-gray-50 dark:bg-gray-700 dark:text-gray-100 dark:border-gray-600 dark:hover:bg-gray-600"
          >
            {t("common.export_csv")}
          </a>
          <Button onClick={panel.kind === "create" ? closeForm : openCreate}>
            {panel.kind === "create" ? t("common.cancel") : t("common.create")}
          </Button>
        </div>
      </div>
      {panel.kind !== "closed" && (
        <div ref={panelRef}>
        <Card>
          <CardHeader>
            <p className="font-medium">{panel.kind === "edit" ? t("common.edit") : t("networth.movements")}</p>
          </CardHeader>
          <CardBody className="space-y-3">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <div>
                <Label>{t("common.date")}</Label>
                <Input
                  type="date"
                  value={draft.movementDate}
                  invalid={!!errors.date}
                  onChange={(e) => { setDraft({ ...draft, movementDate: e.target.value }); if (errors.date) setErrors({ ...errors, date: undefined }); }}
                />
                <FieldError message={errors.date} />
              </div>
              <div>
                <Label>{t("networth.movement_type")}</Label>
                <Select value={draft.type} onChange={(e) => { setDraft({ ...draft, type: e.target.value as MovementInput["type"], assetClassCode: null, liabilityId: null }); if (errors.target) setErrors({ ...errors, target: undefined }); }}>
                  <option value="contribution">{t("networth.contribution")}</option>
                  <option value="withdrawal">{t("networth.withdrawal")}</option>
                  <option value="debt_payment">{t("networth.debt_payment")}</option>
                </Select>
              </div>
              {draft.type !== "debt_payment" ? (
                <div>
                  <Label>{t("common.category")}</Label>
                  <Select
                    value={draft.assetClassCode ?? ""}
                    invalid={!!errors.target}
                    onChange={(e) => { setDraft({ ...draft, assetClassCode: e.target.value || null }); if (errors.target) setErrors({ ...errors, target: undefined }); }}
                  >
                    <option value="">—</option>
                    {assetClasses.map((c) => (
                      <option key={c.code} value={c.code}>
                        {t(`asset.${c.code}`)}
                      </option>
                    ))}
                  </Select>
                  <FieldError message={errors.target} />
                </div>
              ) : (
                <div>
                  <Label>{t("networth.liabilities")}</Label>
                  <Select
                    value={draft.liabilityId ?? ""}
                    invalid={!!errors.target}
                    onChange={(e) => { setDraft({ ...draft, liabilityId: e.target.value || null }); if (errors.target) setErrors({ ...errors, target: undefined }); }}
                  >
                    <option value="">—</option>
                    {liabilities.filter((x) => x.active).map((l) => (
                      <option key={l.id} value={l.id}>{l.name}</option>
                    ))}
                  </Select>
                  <FieldError message={errors.target} />
                </div>
              )}
              <div>
                <Label>{t("common.amount")}</Label>
                <Input
                  type="number"
                  step="0.01"
                  min="0.01"
                  value={draft.amount}
                  invalid={!!errors.amount}
                  onChange={(e) => { setDraft({ ...draft, amount: e.target.value }); if (errors.amount) setErrors({ ...errors, amount: undefined }); }}
                />
                <FieldError message={errors.amount} />
              </div>
              <div className="md:col-span-2">
                <Label>{t("common.description")}</Label>
                <Textarea value={draft.description ?? ""} onChange={(e) => setDraft({ ...draft, description: e.target.value || null })} rows={2} />
              </div>
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={closeForm}>{t("common.cancel")}</Button>
              <Button onClick={save} disabled={create.isPending || update.isPending}>{t("common.save")}</Button>
            </div>
          </CardBody>
        </Card>
        </div>
      )}

      <Card>
        <CardBody>
          {!page || page.items.length === 0 ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
          ) : (
            <>
              <ul className="space-y-2 md:hidden">
                {page.items.map((m) => (
                  <li key={m.id} className="rounded-md border border-border p-3 dark:border-gray-700">
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0 flex-1">
                        <p className="font-medium">{targetLabel(m)}</p>
                        {m.description && (
                          <p className="mt-0.5 truncate text-sm text-gray-600 dark:text-gray-300">{m.description}</p>
                        )}
                        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                          {t(`networth.${m.type}`)} · {formatDate(m.movementDate, i18n.language)}
                        </p>
                      </div>
                      <div className="flex flex-col items-end gap-1">
                        <span className="font-medium">{formatMoney(m.amount, household.currency, i18n.language)}</span>
                        <div className="flex gap-1">
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.edit")}
                            title={t("common.edit")}
                            onClick={() => openEdit(m)}
                          >
                            <span aria-hidden>✏️</span>
                          </Button>
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.delete")}
                            title={t("common.delete")}
                            onClick={() => {
                              if (window.confirm(t("common.delete") + "?")) void del.mutate(m.id);
                            }}
                          >
                            <span aria-hidden>🗑️</span>
                          </Button>
                        </div>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
              <table className="hidden w-full text-sm md:table">
                <thead className="text-left text-gray-500 dark:text-gray-400">
                  <tr>
                    <th className="py-2">{t("common.date")}</th>
                    <th>{t("networth.movement_type")}</th>
                    <th>{t("common.category")}</th>
                    <th>{t("common.description")}</th>
                    <th className="text-right">{t("common.amount")}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((m) => (
                    <tr key={m.id} className="border-t border-border">
                      <td className="py-2">{formatDate(m.movementDate, i18n.language)}</td>
                      <td>{t(`networth.${m.type}`)}</td>
                      <td>{targetLabel(m)}</td>
                      <td className="text-gray-600 dark:text-gray-300">{m.description ?? "—"}</td>
                      <td className="text-right">{formatMoney(m.amount, household.currency, i18n.language)}</td>
                      <td className="text-right">
                        <div className="inline-flex gap-1">
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.edit")}
                            title={t("common.edit")}
                            onClick={() => openEdit(m)}
                          >
                            <span aria-hidden>✏️</span>
                          </Button>
                          <Button
                            variant="ghost"
                            className="px-2"
                            aria-label={t("common.delete")}
                            title={t("common.delete")}
                            onClick={() => {
                              if (window.confirm(t("common.delete") + "?")) void del.mutate(m.id);
                            }}
                          >
                            <span aria-hidden>🗑️</span>
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
