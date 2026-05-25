import { useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "@/auth/AuthContext";
import { asApiError } from "@/api/client";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label } from "@/components/ui/primitives";

export function LoginPage() {
  const { login } = useAuth();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [emailError, setEmailError] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <h1 className="text-lg font-semibold">{t("auth.login")}</h1>
        </CardHeader>
        <CardBody>
          <form
            noValidate
            onSubmit={async (e) => {
              e.preventDefault();
              setError(null);
              setEmailError(null);
              setPasswordError(null);
              let invalid = false;
              if (!email.trim()) { setEmailError(t("errors.field_required")); invalid = true; }
              if (!password) { setPasswordError(t("errors.field_required")); invalid = true; }
              if (invalid) return;
              setSubmitting(true);
              try {
                await login(email, password);
                const params = new URLSearchParams(location.search);
                const next = params.get("next") ?? "/dashboard";
                navigate(next);
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
                invalid={!!emailError || !!error}
                onChange={(e) => { setEmail(e.target.value); if (emailError) setEmailError(null); if (error) setError(null); }}
                autoComplete="email"
              />
              <FieldError message={emailError} />
            </div>
            <div>
              <Label>{t("auth.password")}</Label>
              <Input
                type="password"
                value={password}
                invalid={!!passwordError || !!error}
                onChange={(e) => { setPassword(e.target.value); if (passwordError) setPasswordError(null); if (error) setError(null); }}
                autoComplete="current-password"
              />
              <FieldError message={passwordError} />
            </div>
            <FieldError message={error} />
            <Button type="submit" className="w-full" disabled={submitting}>
              {t("auth.login")}
            </Button>
            <p className="text-center text-sm text-gray-600 dark:text-gray-300">
              {t("auth.no_account")} <Link to="/register" className="text-primary">{t("auth.register_here")}</Link>
            </p>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
