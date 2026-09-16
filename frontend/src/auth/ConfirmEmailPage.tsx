import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { confirmEmailChange } from "@/api/auth";
import { apiErrorMessage, asApiError } from "@/api/client";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";

/** The link lands here, usually on the device holding the mailbox rather than the signed-in one. The token
 *  is the whole proof, so nothing is asked for: confirm and send the user to log in. */
export function ConfirmEmailPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get("token") ?? "";
  const [error, setError] = useState<string | null>(token ? null : t("auth.email_change_link_invalid"));

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    void (async () => {
      try {
        await confirmEmailChange(token);
        if (!cancelled) navigate("/login?email=changed", { replace: true });
      } catch (err) {
        if (cancelled) return;
        const invalid = asApiError(err).code === "EMAIL_CHANGE_TOKEN_INVALID";
        setError(invalid ? t("auth.email_change_link_invalid") : apiErrorMessage(err, t));
      }
    })();
    return () => { cancelled = true; };
  }, [token, navigate, t]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas px-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <h1 className="text-lg font-semibold">{t("auth.confirm_email")}</h1>
        </CardHeader>
        <CardBody className="space-y-4">
          {error ? (
            <p role="alert" className="text-sm text-gray-700 dark:text-gray-200">{error}</p>
          ) : (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
          )}
          <p className="text-center text-sm text-gray-600 dark:text-gray-300">
            <Link to="/login" className="text-primary">{t("auth.back_to_login")}</Link>
          </p>
        </CardBody>
      </Card>
    </div>
  );
}
