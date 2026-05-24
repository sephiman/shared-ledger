import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useAssetClasses } from "@/api/catalog";
import {
  useCreateMovement,
  useDeleteMovement,
  useLiabilities,
  useMovements,
  type MovementInput,
} from "@/api/networth";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select, Textarea } from "@/components/ui/primitives";
import { formatDate, isoToday } from "@/lib/dates";
import { formatMoney } from "@/lib/money";

export function MovementsTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: page } = useMovements(household.householdId, { size: 100 });
  const { data: assetClasses = [] } = useAssetClasses();
  const { data: liabilities = [] } = useLiabilities(household.householdId);
  const create = useCreateMovement(household.householdId);
  const del = useDeleteMovement(household.householdId);
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<MovementInput>({
    movementDate: isoToday(),
    type: "contribution",
    assetClassCode: null,
    liabilityId: null,
    amount: "",
    description: null,
  });
  const [errors, setErrors] = useState<{ date?: string; target?: string; amount?: string }>({});

  function openForm() {
    setOpen(true);
    setErrors({});
  }

  function closeForm() {
    setOpen(false);
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
    await create.mutateAsync(normalized);
    setOpen(false);
    setErrors({});
    setDraft({ ...draft, amount: "", description: null });
  }

  function targetLabel(m: { type: string; assetClassCode: string | null; liabilityId: string | null }) {
    if (m.assetClassCode) return t(`asset.${m.assetClassCode}`);
    if (m.liabilityId) return liabilities.find((x) => x.id === m.liabilityId)?.name ?? m.liabilityId;
    return "—";
  }

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm text-gray-500">{t("networth.movements_description")}</p>
        <div className="flex gap-2">
          <a
            href={`/api/households/${household.householdId}/movements/export.csv`}
            download
            className="inline-flex items-center justify-center rounded-md border border-border bg-white px-4 py-2 text-sm font-medium text-gray-900 hover:bg-gray-50 dark:bg-gray-700 dark:text-gray-100 dark:border-gray-600 dark:hover:bg-gray-600"
          >
            {t("common.export_csv")}
          </a>
          <Button onClick={openForm}>{t("common.create")}</Button>
        </div>
      </div>
      {open && (
        <Card>
          <CardHeader>
            <p className="font-medium">{t("networth.movements")}</p>
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
                <Textarea value={draft.description ?? ""} onChange={(e) => setDraft({ ...draft, description: e.target.value })} rows={2} />
              </div>
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={closeForm}>{t("common.cancel")}</Button>
              <Button onClick={save}>{t("common.save")}</Button>
            </div>
          </CardBody>
        </Card>
      )}

      <Card>
        <CardBody>
          {!page || page.items.length === 0 ? (
            <p className="text-gray-500">{t("common.empty")}</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="text-left text-gray-500">
                <tr>
                  <th className="py-2">{t("common.date")}</th>
                  <th>{t("networth.movement_type")}</th>
                  <th>{t("common.category")}</th>
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
                    <td className="text-right">{formatMoney(m.amount, household.currency, i18n.language)}</td>
                    <td className="text-right">
                      <Button
                        variant="ghost"
                        onClick={() => {
                          if (window.confirm(t("common.delete") + "?")) void del.mutate(m.id);
                        }}
                      >
                        {t("common.delete")}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
