import { useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { requestPasswordReset, useAuthFeatures } from "@/api/auth";
import { apiErrorMessage } from "@/api/client";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label } from "@/components/ui/primitives";

export function ForgotPasswordPage() {
  const { t } = useTranslation();
  const features = useAuthFeatures();
  const [email, setEmail] = useState("");
  const [emailError, setEmailError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);

  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas px-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <h1 className="text-lg font-semibold">{t("auth.forgot_password")}</h1>
        </CardHeader>
        <CardBody className="space-y-4">
          {features.data && !features.data.passwordReset ? (
            <p className="text-sm text-gray-600 dark:text-gray-300">{t("auth.reset_unavailable")}</p>
          ) : sent ? (
            // The same text whether or not the address is known; the server answers identically too.
            <p role="status" className="text-sm text-gray-700 dark:text-gray-200">{t("auth.reset_requested")}</p>
          ) : (
            <form
              noValidate
              onSubmit={async (e) => {
                e.preventDefault();
                setError(null);
                const trimmed = email.trim();
                if (!trimmed) { setEmailError(t("errors.field_required")); return; }
                if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed)) { setEmailError(t("errors.email_invalid")); return; }
                setSubmitting(true);
                try {
                  await requestPasswordReset(trimmed);
                  setSent(true);
                } catch (err) {
                  setError(apiErrorMessage(err, t));
                } finally {
                  setSubmitting(false);
                }
              }}
              className="space-y-4"
            >
              <p className="text-sm text-gray-600 dark:text-gray-300">{t("auth.forgot_password_intro")}</p>
              <div>
                <Label htmlFor="forgot-email">{t("auth.email")}</Label>
                <Input
                  id="forgot-email"
                  type="email"
                  value={email}
                  invalid={!!emailError}
                  onChange={(e) => { setEmail(e.target.value); if (emailError) setEmailError(null); }}
                  autoComplete="email"
                />
                <FieldError message={emailError} />
              </div>
              <FieldError message={error} />
              <Button type="submit" className="w-full" disabled={submitting}>
                {t("auth.send_reset_link")}
              </Button>
            </form>
          )}
          <p className="text-center text-sm text-gray-600 dark:text-gray-300">
            <Link to="/login" className="text-primary">{t("auth.back_to_login")}</Link>
          </p>
        </CardBody>
      </Card>
    </div>
  );
}
