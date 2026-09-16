import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { requestEmailChange, useAuthFeatures } from "@/api/auth";
import { apiErrorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthContext";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label } from "@/components/ui/primitives";
import { showToast } from "@/lib/toastBus";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Moving the account to another address. With SMTP configured nothing changes until the link mailed to
 *  the new address is opened; without it the password check alone carries the change and it applies at
 *  once. Either way the current password is required — a stolen session cookie must not be enough to take
 *  over the account's recovery channel. */
export function EmailChangeCard() {
  const { t } = useTranslation();
  const { user, refresh } = useAuth();
  const features = useAuthFeatures();
  const [newEmail, setNewEmail] = useState("");
  const [password, setPassword] = useState("");
  const [emailError, setEmailError] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [pendingFor, setPendingFor] = useState<string | null>(null);

  if (!user) return null;

  const verified = features.data?.emailChangeVerified ?? false;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setPendingFor(null);
    const trimmed = newEmail.trim();
    let invalid = false;
    if (!trimmed) { setEmailError(t("errors.field_required")); invalid = true; }
    else if (!EMAIL_PATTERN.test(trimmed)) { setEmailError(t("errors.email_invalid")); invalid = true; }
    if (!password) { setPasswordError(t("errors.field_required")); invalid = true; }
    if (invalid) return;
    setSubmitting(true);
    try {
      const result = await requestEmailChange(trimmed, password);
      setNewEmail("");
      setPassword("");
      if (result.status === "pending") {
        setPendingFor(result.email);
      } else {
        // The server rebuilt the session under the new principal; pick the new identity up.
        await refresh();
        showToast(t("settings.email_changed"));
      }
    } catch (err) {
      setError(apiErrorMessage(err, t));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("settings.change_email")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("settings.current_email", { email: user.email })}</p>
      </CardHeader>
      <CardBody>
        <form noValidate onSubmit={submit} className="max-w-xs space-y-3">
          <p className="text-sm text-gray-600 dark:text-gray-300">
            {verified ? t("settings.change_email_verified_hint") : t("settings.change_email_direct_hint")}
          </p>
          {pendingFor && (
            <p role="status" className="rounded-md bg-item-hover px-3 py-2 text-sm text-gray-700 dark:text-gray-200">
              {t("settings.email_change_pending", { email: pendingFor })}
            </p>
          )}
          <div>
            <Label htmlFor="new-email">{t("settings.new_email")}</Label>
            <Input
              id="new-email"
              type="email"
              autoComplete="email"
              value={newEmail}
              invalid={!!emailError}
              onChange={(e) => { setNewEmail(e.target.value); if (emailError) setEmailError(null); }}
            />
            <FieldError message={emailError} />
          </div>
          <div>
            <Label htmlFor="email-current-password">{t("auth.current_password")}</Label>
            <Input
              id="email-current-password"
              type="password"
              autoComplete="current-password"
              value={password}
              invalid={!!passwordError}
              onChange={(e) => { setPassword(e.target.value); if (passwordError) setPasswordError(null); }}
            />
            <FieldError message={passwordError} />
          </div>
          <FieldError message={error} />
          <Button type="submit" disabled={submitting}>{t("settings.change_email_cta")}</Button>
        </form>
      </CardBody>
    </Card>
  );
}
