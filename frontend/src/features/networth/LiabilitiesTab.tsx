import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useDeleteLiability, useLiabilities, useUpsertLiability } from "@/api/networth";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label } from "@/components/ui/primitives";

export function LiabilitiesTab() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const { data: items = [] } = useLiabilities(household.householdId);
  const upsert = useUpsertLiability(household.householdId);
  const del = useDeleteLiability(household.householdId);
  const [editing, setEditing] = useState<{ id?: string; name: string; active: boolean } | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);

  function startEdit(value: { id?: string; name: string; active: boolean } | null) {
    setEditing(value);
    setNameError(null);
  }

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("networth.liabilities_description")}</p>
        <Button onClick={() => startEdit({ name: "", active: true })}>{t("networth.new_liability")}</Button>
      </div>
      {editing && (
        <Card>
          <CardHeader>
            <p className="font-medium">{editing.id ? t("common.edit") : t("networth.new_liability")}</p>
          </CardHeader>
          <CardBody className="space-y-3">
            <div>
              <Label>{t("networth.liability_name")}</Label>
              <Input
                value={editing.name}
                invalid={!!nameError}
                onChange={(e) => { setEditing({ ...editing, name: e.target.value }); if (nameError) setNameError(null); }}
              />
              <FieldError message={nameError} />
            </div>
            <div className="flex items-center gap-2">
              <input id="liab-active" type="checkbox" checked={editing.active} onChange={(e) => setEditing({ ...editing, active: e.target.checked })} />
              <Label htmlFor="liab-active" className="mb-0">{t("networth.liability_active")}</Label>
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => startEdit(null)}>{t("common.cancel")}</Button>
              <Button
                onClick={async () => {
                  if (!editing.name.trim()) {
                    setNameError(t("errors.field_required"));
                    return;
                  }
                  await upsert.mutateAsync(editing);
                  startEdit(null);
                }}
              >
                {t("common.save")}
              </Button>
            </div>
          </CardBody>
        </Card>
      )}
      <Card>
        <CardBody>
          {items.length === 0 ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="text-left text-gray-500 dark:text-gray-400">
                <tr>
                  <th className="py-2">{t("networth.liability_name")}</th>
                  <th>{t("common.active")}</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {items.map((it) => (
                  <tr key={it.id} className="border-t border-border">
                    <td className="py-2">{it.name}</td>
                    <td>{it.active ? t("common.yes") : t("common.no")}</td>
                    <td className="space-x-2 text-right">
                      <Button variant="ghost" onClick={() => startEdit({ id: it.id, name: it.name, active: it.active })}>{t("common.edit")}</Button>
                      <Button
                        variant="ghost"
                        onClick={() => {
                          if (window.confirm(t("common.delete") + "?")) void del.mutate(it.id);
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
