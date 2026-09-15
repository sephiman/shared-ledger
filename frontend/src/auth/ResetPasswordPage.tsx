import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { confirmPasswordReset, useAuthFeatures, validatePasswordResetToken } from "@/api/auth";
import { apiErrorMessage, asApiError } from "@/api/client";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label } from "@/components/ui/primitives";

type TokenState = "checking" | "valid" | "invalid";

export function ResetPasswordPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const features = useAuthFeatures();
  const [params] = useSearchParams();
  const token = params.get("token") ?? "";
  const [tokenState, setTokenState] = useState<TokenState>(token ? "checking" : "invalid");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [confirmationError, setConfirmationError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    void (async () => {
      try {
        await validatePasswordResetToken(token);
        if (!cancelled) setTokenState("valid");
      } catch {
        // Unknown, expired and used all come back as one code; the page never learns which.
        if (!cancelled) setTokenState("invalid");
      }
    })();
    return () => { cancelled = true; };
  }, [token]);

  const unavailable = features.data ? !features.data.passwordReset : false;

  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas px-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <h1 className="text-lg font-semibold">{t("auth.reset_password")}</h1>
        </CardHeader>
        <CardBody className="space-y-4">
          {unavailable ? (
            <p className="text-sm text-gray-600 dark:text-gray-300">{t("auth.reset_unavailable")}</p>
          ) : tokenState === "checking" ? (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
          ) : tokenState === "invalid" ? (
            <>
              <p role="alert" className="text-sm text-gray-700 dark:text-gray-200">{t("auth.reset_link_invalid")}</p>
              <Link to="/forgot-password" className="text-sm text-primary">{t("auth.request_new_link")}</Link>
            </>
          ) : (
            <form
              noValidate
              onSubmit={async (e) => {
                e.preventDefault();
                setError(null);
                let invalid = false;
                if (!password) { setPasswordError(t("errors.field_required")); invalid = true; }
                else if (password.length < 8) { setPasswordError(t("errors.password_too_short")); invalid = true; }
                if (confirmation !== password) { setConfirmationError(t("errors.password_confirmation_mismatch")); invalid = true; }
                if (invalid) return;
                setSubmitting(true);
                try {
                  await confirmPasswordReset(token, password);
                  navigate("/login?reset=done", { replace: true });
                } catch (err) {
                  if (asApiError(err).code === "PASSWORD_RESET_TOKEN_INVALID") setTokenState("invalid");
                  else setError(apiErrorMessage(err, t));
                } finally {
                  setSubmitting(false);
                }
              }}
              className="space-y-4"
            >
              <div>
                <Label htmlFor="reset-new-password">{t("auth.new_password")}</Label>
                <Input
                  id="reset-new-password"
                  type="password"
                  value={password}
                  invalid={!!passwordError}
                  onChange={(e) => { setPassword(e.target.value); if (passwordError) setPasswordError(null); }}
                  autoComplete="new-password"
                  minLength={8}
                />
                <FieldError message={passwordError} />
              </div>
              <div>
                <Label htmlFor="reset-confirm-password">{t("auth.confirm_new_password")}</Label>
                <Input
                  id="reset-confirm-password"
                  type="password"
                  value={confirmation}
                  invalid={!!confirmationError}
                  onChange={(e) => { setConfirmation(e.target.value); if (confirmationError) setConfirmationError(null); }}
                  autoComplete="new-password"
                />
                <FieldError message={confirmationError} />
              </div>
              <FieldError message={error} />
              <Button type="submit" className="w-full" disabled={submitting}>
                {t("auth.set_new_password")}
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
