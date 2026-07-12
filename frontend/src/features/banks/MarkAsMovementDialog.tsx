import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { apiErrorMessage } from "@/api/client";
import { useConfirmAsMovement, type MovementType, type PendingMovement } from "@/api/banks";
import { useAssetClasses } from "@/api/catalog";
import { useLiabilities } from "@/api/networth";
import { formatMoney } from "@/lib/money";
import { formatDate } from "@/lib/dates";
import { showToast } from "@/lib/toastBus";
import { Button, Card, CardBody, CardHeader, FieldError, Label, Select } from "@/components/ui/primitives";

interface Props {
  open: boolean;
  householdId: string;
  movement: PendingMovement;
  currency: string;
  locale: string;
  onClose: () => void;
}

// Preselect the movement type from the bank direction: money arriving is a withdrawal from an asset;
// money leaving defaults to a contribution (the user can switch to a debt payment). See the backend
// confirm-as-movement contract in PendingMovementService.
function defaultType(direction: PendingMovement["direction"]): MovementType {
  return direction === "income" ? "withdrawal" : "contribution";
}

export function MarkAsMovementDialog({ open, householdId, movement, currency, locale, onClose }: Props) {
  const { t, i18n } = useTranslation();
  const { data: assetClasses = [] } = useAssetClasses();
  const { data: liabilities = [] } = useLiabilities(householdId);
  const confirmAsMovement = useConfirmAsMovement(householdId);

  const [type, setType] = useState<MovementType>(defaultType(movement.direction));
  const [assetClassCode, setAssetClassCode] = useState<string | null>(null);
  const [liabilityId, setLiabilityId] = useState<string | null>(null);
  const [error, setError] = useState<string | undefined>();
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setType(defaultType(movement.direction));
    setAssetClassCode(null);
    setLiabilityId(null);
    setError(undefined);
    setSubmitError(null);
  }, [open, movement.direction, movement.id]);

  if (!open) return null;

  const isDebt = type === "debt_payment";

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isDebt ? !liabilityId : !assetClassCode) {
      setError(t("errors.select_required"));
      return;
    }
    try {
      await confirmAsMovement.mutateAsync({
        id: movement.id,
        input: {
          type,
          assetClassCode: isDebt ? null : assetClassCode,
          liabilityId: isDebt ? liabilityId : null,
        },
      });
      showToast(t("banks.marked_as_movement"), "success");
      onClose();
    } catch (err) {
      setSubmitError(apiErrorMessage(err, t));
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-black/40 px-4 py-8"
      role="dialog"
      aria-modal="true"
      onClick={onClose}
    >
      <Card className="w-full max-w-md" onClick={(e) => e.stopPropagation()}>
        <CardHeader>
          <p className="font-medium">{t("banks.mark_as_movement")}</p>
        </CardHeader>
        <CardBody>
          <form noValidate onSubmit={submit} className="space-y-3">
            {/* Read-only summary of the pending item — date and amount carry over unchanged. */}
            <div className="rounded-md border border-border p-3 text-sm dark:border-gray-700">
              <div className="flex items-center justify-between">
                <span className="font-medium">{movement.counterparty ?? t("banks.no_counterparty")}</span>
                <span className="font-semibold">{formatMoney(movement.amount, currency, locale)}</span>
              </div>
              <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                {formatDate(movement.bookingDate, i18n.language)}
              </p>
            </div>

            <div>
              <Label>{t("networth.movement_type")}</Label>
              <Select
                value={type}
                onChange={(e) => {
                  setType(e.target.value as MovementType);
                  setAssetClassCode(null);
                  setLiabilityId(null);
                  setError(undefined);
                }}
              >
                <option value="contribution">{t("networth.contribution")}</option>
                <option value="withdrawal">{t("networth.withdrawal")}</option>
                <option value="debt_payment">{t("networth.debt_payment")}</option>
              </Select>
            </div>

            {isDebt ? (
              <div>
                <Label>{t("networth.liabilities")}</Label>
                <Select
                  value={liabilityId ?? ""}
                  invalid={!!error}
                  onChange={(e) => { setLiabilityId(e.target.value || null); setError(undefined); }}
                >
                  <option value="">—</option>
                  {liabilities.filter((l) => l.active).map((l) => (
                    <option key={l.id} value={l.id}>{l.name}</option>
                  ))}
                </Select>
                <FieldError message={error} />
              </div>
            ) : (
              <div>
                <Label>{t("common.category")}</Label>
                <Select
                  value={assetClassCode ?? ""}
                  invalid={!!error}
                  onChange={(e) => { setAssetClassCode(e.target.value || null); setError(undefined); }}
                >
                  <option value="">—</option>
                  {assetClasses.map((c) => (
                    <option key={c.code} value={c.code}>{t(`asset.${c.code}`)}</option>
                  ))}
                </Select>
                <FieldError message={error} />
              </div>
            )}

            {submitError && <FieldError message={submitError} />}

            <div className="flex justify-end gap-2 pt-1">
              <Button type="button" variant="ghost" onClick={onClose}>{t("common.cancel")}</Button>
              <Button type="submit" disabled={confirmAsMovement.isPending}>{t("banks.confirm")}</Button>
            </div>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
