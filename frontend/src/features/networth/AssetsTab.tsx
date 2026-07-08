import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  useAddAssetValue,
  useAssetValues,
  useAssets,
  useDeleteAsset,
  useDeleteAssetValue,
  useUpdateAssetValue,
  useUpsertAsset,
  type AssetType,
} from "@/api/networth";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { NamedValueCard, type DetailsDraft } from "./NamedValueCard";

const ASSET_TYPES: AssetType[] = ["property", "vehicle", "other"];

export function AssetsTab() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const hid = household.householdId;
  const { data: items = [] } = useAssets(hid);
  const upsert = useUpsertAsset(hid);
  const del = useDeleteAsset(hid);
  // Top form is create-only; existing assets are edited inside their own card (one editor at a time).
  const [creating, setCreating] = useState<{ name: string; type: AssetType; active: boolean } | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);

  async function saveDetails(id: string, d: DetailsDraft) {
    await upsert.mutateAsync({ id, name: d.name, type: d.type ?? "other", active: d.active });
  }

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("networth.assets_description")}</p>
        <div className="flex gap-2">
          <a
            href={`/api/households/${hid}/assets/export.csv`}
            download
            className="inline-flex items-center justify-center rounded-md border border-border bg-white px-4 py-2 text-sm font-medium text-gray-900 hover:bg-gray-50 dark:bg-gray-700 dark:text-gray-100 dark:border-gray-600 dark:hover:bg-gray-600"
          >
            {t("common.export_csv")}
          </a>
          <Button onClick={() => { setCreating({ name: "", type: "property", active: true }); setNameError(null); }}>{t("networth.new_asset")}</Button>
        </div>
      </div>

      {creating && (
        <Card>
          <CardHeader><p className="font-medium">{t("networth.new_asset")}</p></CardHeader>
          <CardBody className="space-y-3">
            <div>
              <Label>{t("networth.asset_name")}</Label>
              <Input value={creating.name} invalid={!!nameError} onChange={(e) => { setCreating({ ...creating, name: e.target.value }); if (nameError) setNameError(null); }} />
              <FieldError message={nameError} />
            </div>
            <div>
              <Label>{t("networth.asset_type")}</Label>
              <Select value={creating.type} onChange={(e) => setCreating({ ...creating, type: e.target.value as AssetType })}>
                {ASSET_TYPES.map((tp) => <option key={tp} value={tp}>{t(`networth.asset_type_${tp}`)}</option>)}
              </Select>
            </div>
            <div className="flex items-center gap-2">
              <input id="asset-new-active" type="checkbox" checked={creating.active} onChange={(e) => setCreating({ ...creating, active: e.target.checked })} />
              <Label htmlFor="asset-new-active" className="mb-0">{t("networth.asset_active")}</Label>
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setCreating(null)}>{t("common.cancel")}</Button>
              <Button
                onClick={async () => {
                  if (!creating.name.trim()) { setNameError(t("errors.field_required")); return; }
                  await upsert.mutateAsync({ name: creating.name, type: creating.type, active: creating.active });
                  setCreating(null);
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
            <ul className="space-y-2">
              {items.map((it) => (
                <NamedValueCard
                  key={it.id}
                  kind="asset"
                  item={it}
                  useValues={useAssetValues}
                  useAdd={useAddAssetValue}
                  useUpdate={useUpdateAssetValue}
                  useDelete={useDeleteAssetValue}
                  onSaveDetails={(d) => saveDetails(it.id, d)}
                  onDelete={() => del.mutate(it.id)}
                />
              ))}
            </ul>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
