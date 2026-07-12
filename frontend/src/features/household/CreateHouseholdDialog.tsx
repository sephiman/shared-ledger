import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useCreateHousehold } from "@/api/settings";
import { useAuth } from "@/auth/AuthContext";
import { apiErrorMessage } from "@/api/client";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { getCurrencyOptions } from "@/lib/currency";

interface Props {
  open: boolean;
  onClose: () => void;
  onCreated: (householdId: string) => void;
}

export function CreateHouseholdDialog({ open, onClose, onCreated }: Props) {
  const { t, i18n } = useTranslation();
  const { user, refresh } = useAuth();
  const create = useCreateHousehold();
  const [name, setName] = useState("");
  const [currency, setCurrency] = useState("EUR");
  const [defaultLocale, setDefaultLocale] = useState<"en" | "es">(i18n.language.startsWith("es") ? "es" : "en");
  const [errors, setErrors] = useState<{ name?: string; currency?: string }>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const currencyOptions = useMemo(() => getCurrencyOptions(i18n.language), [i18n.language]);

  if (!open) return null;

  const reset = () => {
    setName("");
    setCurrency("EUR");
    setErrors({});
    setSubmitError(null);
  };

  const close = () => {
    reset();
    onClose();
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4"
      role="dialog"
      aria-modal="true"
      onClick={close}
    >
      <Card className="w-full max-w-md" onClick={(e) => e.stopPropagation()}>
        <CardHeader>
          <p className="font-medium">{t("household.create_title")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("settings.create_household_description")}</p>
        </CardHeader>
        <CardBody>
          <form
            noValidate
            onSubmit={async (e) => {
              e.preventDefault();
              const next: typeof errors = {};
              const trimmedName = name.trim();
              const trimmedCurrency = currency.trim().toUpperCase();
              if (!trimmedName) next.name = t("errors.field_required");
              if (!trimmedCurrency) next.currency = t("errors.field_required");
              if (Object.keys(next).length > 0) {
                setErrors(next);
                return;
              }
              setErrors({});
              setSubmitError(null);
              try {
                const wasFirstHousehold = (user?.households?.length ?? 0) === 0;
                const created = await create.mutateAsync({
                  name: trimmedName,
                  currency: trimmedCurrency,
                  defaultLocale,
                });
                // The backend adopts the chosen language as the profile locale for the
                // user's first household; mirror that in the live UI so it switches now.
                if (wasFirstHousehold) {
                  await i18n.changeLanguage(defaultLocale);
                  await refresh();
                }
                onCreated(created.id);
                reset();
              } catch (err) {
                setSubmitError(apiErrorMessage(err, t));
              }
            }}
            className="space-y-3"
          >
            <div>
              <Label>{t("auth.household_name")}</Label>
              <Input
                value={name}
                invalid={!!errors.name}
                autoFocus
                onChange={(e) => { setName(e.target.value); if (errors.name) setErrors({ ...errors, name: undefined }); }}
              />
              <FieldError message={errors.name} />
            </div>
            <div>
              <Label>{t("auth.household_currency")}</Label>
              <Select
                value={currency}
                invalid={!!errors.currency}
                onChange={(e) => { setCurrency(e.target.value); if (errors.currency) setErrors({ ...errors, currency: undefined }); }}
              >
                {currencyOptions.map((opt) => (
                  <option key={opt.code} value={opt.code}>{opt.label}</option>
                ))}
              </Select>
              <FieldError message={errors.currency} />
            </div>
            <div>
              <Label>{t("auth.household_locale")}</Label>
              <Select value={defaultLocale} onChange={(e) => setDefaultLocale(e.target.value as "en" | "es")}>
                <option value="en">English</option>
                <option value="es">Español</option>
              </Select>
            </div>
            <FieldError message={submitError} />
            <div className="flex justify-end gap-2 pt-2">
              <Button type="button" variant="secondary" onClick={close}>
                {t("common.cancel")}
              </Button>
              <Button type="submit" disabled={create.isPending}>
                {t("household.create_submit")}
              </Button>
            </div>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
