import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "@/auth/AuthContext";
import { apiClient, asApiError, seedCsrf } from "@/api/client";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { getCurrencyOptions } from "@/lib/currency";

interface PublicInvite {
  householdName: string;
  role: string;
  expiresAt: string;
}

export function RegisterPage() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const { refresh } = useAuth();
  const [params] = useSearchParams();
  const invitationToken = params.get("invite");
  const [invite, setInvite] = useState<PublicInvite | null>(null);
  const [inviteError, setInviteError] = useState<string | null>(null);

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [locale, setLocale] = useState<"en" | "es">(i18n.language.startsWith("es") ? "es" : "en");
  const [householdName, setHouseholdName] = useState("");
  const [currency, setCurrency] = useState("EUR");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string; householdName?: string; currency?: string }>({});
  const currencyOptions = useMemo(() => getCurrencyOptions(i18n.language), [i18n.language]);

  useEffect(() => {
    void (async () => {
      await seedCsrf();
      if (invitationToken) {
        try {
          const res = await apiClient.get<PublicInvite>(`/invitations/${invitationToken}`);
          setInvite(res.data);
        } catch (err) {
          const api = asApiError(err);
          setInviteError(t(`errors.${api.code}`, api.message));
        }
      }
    })();
  }, [invitationToken, t]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4 py-8">
      <Card className="w-full max-w-md">
        <CardHeader>
          <h1 className="text-lg font-semibold">{t("auth.register")}</h1>
          {invite && (
            <p className="mt-1 text-sm text-gray-600 dark:text-gray-300">
              {t("auth.register_with_invite", { household: invite.householdName, role: invite.role })}
            </p>
          )}
          {inviteError && <FieldError message={inviteError} />}
        </CardHeader>
        <CardBody>
          <form
            noValidate
            onSubmit={async (e) => {
              e.preventDefault();
              setError(null);
              const next: typeof fieldErrors = {};
              const trimmedEmail = email.trim();
              if (!trimmedEmail) next.email = t("errors.field_required");
              else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) next.email = t("errors.email_invalid");
              if (!password) next.password = t("errors.field_required");
              else if (password.length < 8) next.password = t("errors.password_too_short");
              if (!invitationToken) {
                if (!householdName.trim()) next.householdName = t("errors.field_required");
                if (!currency.trim()) next.currency = t("errors.field_required");
              }
              if (Object.keys(next).length > 0) {
                setFieldErrors(next);
                return;
              }
              setFieldErrors({});
              setSubmitting(true);
              try {
                const body: Record<string, unknown> = { email, password, locale };
                if (invitationToken) body.invitationToken = invitationToken;
                else body.household = { name: householdName, currency: currency.toUpperCase(), defaultLocale: locale };
                await apiClient.post("/auth/register", body);
                // Auto-login
                await apiClient.post("/auth/login", { email, password, rememberMe: true });
                await refresh();
                navigate("/dashboard");
              } catch (err) {
                const api = asApiError(err);
                setError(t(`errors.${api.code}`, api.message));
              } finally {
                setSubmitting(false);
              }
            }}
            className="space-y-4"
          >
            <div>
              <Label>{t("auth.email")}</Label>
              <Input
                type="email"
                value={email}
                invalid={!!fieldErrors.email}
                onChange={(e) => { setEmail(e.target.value); if (fieldErrors.email) setFieldErrors({ ...fieldErrors, email: undefined }); }}
              />
              <FieldError message={fieldErrors.email} />
            </div>
            <div>
              <Label>{t("auth.password")}</Label>
              <Input
                type="password"
                value={password}
                invalid={!!fieldErrors.password}
                onChange={(e) => { setPassword(e.target.value); if (fieldErrors.password) setFieldErrors({ ...fieldErrors, password: undefined }); }}
                minLength={8}
              />
              <FieldError message={fieldErrors.password} />
            </div>
            <div>
              <Label>{t("auth.household_locale")}</Label>
              <Select value={locale} onChange={(e) => setLocale(e.target.value as "en" | "es")}>
                <option value="en">English</option>
                <option value="es">Español</option>
              </Select>
            </div>
            {!invitationToken && (
              <div className="space-y-3 rounded border border-border p-3">
                <p className="text-sm font-medium text-gray-700 dark:text-gray-200">{t("auth.household_section")}</p>
                <div>
                  <Label>{t("auth.household_name")}</Label>
                  <Input
                    value={householdName}
                    invalid={!!fieldErrors.householdName}
                    onChange={(e) => { setHouseholdName(e.target.value); if (fieldErrors.householdName) setFieldErrors({ ...fieldErrors, householdName: undefined }); }}
                  />
                  <FieldError message={fieldErrors.householdName} />
                </div>
                <div>
                  <Label>{t("auth.household_currency")}</Label>
                  <Select
                    value={currency}
                    invalid={!!fieldErrors.currency}
                    onChange={(e) => { setCurrency(e.target.value); if (fieldErrors.currency) setFieldErrors({ ...fieldErrors, currency: undefined }); }}
                  >
                    {currencyOptions.map((opt) => (
                      <option key={opt.code} value={opt.code}>{opt.label}</option>
                    ))}
                  </Select>
                  <FieldError message={fieldErrors.currency} />
                </div>
              </div>
            )}
            <FieldError message={error} />
            <Button type="submit" className="w-full" disabled={submitting}>
              {t("auth.register")}
            </Button>
            <p className="text-center text-sm text-gray-600 dark:text-gray-300">
              {t("auth.have_account")} <Link to="/login" className="text-primary">{t("auth.login_here")}</Link>
            </p>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
