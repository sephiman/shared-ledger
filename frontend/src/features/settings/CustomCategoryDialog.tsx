import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { apiErrorMessage } from "@/api/client";
import { useCreateCustomCategory, useUpdateCustomCategory, type Category } from "@/api/catalog";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { groupIcon } from "@/lib/categoryGroup";

const EXPENSE_GROUPS = ["home", "transport", "groceries", "shopping", "outings", "financial", "health", "personal"] as const;

interface Props {
  open: boolean;
  householdId: string;
  editing: Category | null;
  onClose: () => void;
  onSaved: () => void;
}

export function CustomCategoryDialog({ open, householdId, editing, onClose, onSaved }: Props) {
  const { t } = useTranslation();
  const create = useCreateCustomCategory(householdId);
  const update = useUpdateCustomCategory(householdId);

  const [name, setName] = useState("");
  const [kind, setKind] = useState<"income" | "expense">("expense");
  const [groupCode, setGroupCode] = useState<string>(EXPENSE_GROUPS[0]);
  const [essential, setEssential] = useState(false);
  const [nameError, setNameError] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    if (editing) {
      setName(editing.name);
      setKind(editing.kind);
      setGroupCode(editing.group ?? EXPENSE_GROUPS[0]);
      setEssential(editing.essential);
    } else {
      setName("");
      setKind("expense");
      setGroupCode(EXPENSE_GROUPS[0]);
      setEssential(false);
    }
    setNameError(null);
    setSubmitError(null);
  }, [open, editing]);

  if (!open) return null;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) {
      setNameError(t("errors.field_required"));
      return;
    }
    setNameError(null);
    setSubmitError(null);
    try {
      if (editing) {
        await update.mutateAsync({
          code: editing.code,
          patch: {
            name: trimmed !== editing.name ? trimmed : undefined,
            groupCode: editing.kind === "expense" && groupCode !== editing.group ? groupCode : undefined,
            essential: essential !== editing.essential ? essential : undefined,
          },
        });
      } else {
        await create.mutateAsync({
          name: trimmed,
          kind,
          groupCode: kind === "expense" ? groupCode : null,
          essential,
        });
      }
      onSaved();
    } catch (err) {
      setSubmitError(apiErrorMessage(err, t));
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4"
      role="dialog"
      aria-modal="true"
      onClick={onClose}
    >
      <Card className="w-full max-w-md" onClick={(e) => e.stopPropagation()}>
        <CardHeader>
          <p className="font-medium">
            {editing ? t("settings.edit_custom_category") : t("settings.add_custom_category")}
          </p>
        </CardHeader>
        <CardBody>
          <form noValidate onSubmit={submit} className="space-y-3">
            <div>
              <Label>{t("settings.custom_category_name")}</Label>
              <Input
                value={name}
                invalid={!!nameError}
                autoFocus
                maxLength={80}
                onChange={(e) => { setName(e.target.value); if (nameError) setNameError(null); }}
              />
              <FieldError message={nameError} />
            </div>
            <div>
              <Label>{t("common.direction")}</Label>
              <Select
                value={kind}
                disabled={!!editing}
                onChange={(e) => setKind(e.target.value as "income" | "expense")}
              >
                <option value="expense">{t("common.expense")}</option>
                <option value="income">{t("common.income")}</option>
              </Select>
            </div>
            {kind === "expense" && (
              <div>
                <Label>{t("settings.custom_category_group")}</Label>
                <Select value={groupCode} onChange={(e) => setGroupCode(e.target.value)}>
                  {EXPENSE_GROUPS.map((g) => (
                    <option key={g} value={g}>{groupIcon(g)} {t(`category_group.${g}`)}</option>
                  ))}
                </Select>
              </div>
            )}
            <div className="flex items-center gap-2">
              <input
                id="essential"
                type="checkbox"
                checked={essential}
                onChange={(e) => setEssential(e.target.checked)}
              />
              <Label htmlFor="essential" className="mb-0">
                {t("settings.custom_category_essential")}
              </Label>
            </div>
            <FieldError message={submitError} />
            <div className="flex justify-end gap-2 pt-2">
              <Button type="button" variant="secondary" onClick={onClose}>
                {t("common.cancel")}
              </Button>
              <Button type="submit" disabled={create.isPending || update.isPending}>
                {t("common.save")}
              </Button>
            </div>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
