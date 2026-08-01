import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useCompleteLink } from "@/api/banks";
import { apiErrorMessage } from "@/api/client";
import { showToast } from "@/lib/toastBus";
import { Card, CardBody } from "@/components/ui/primitives";

/** Landing route the bank redirects to after SCA: reads `code`+`state`, completes the link for the active
 *  household, returns to Settings. State validation happens server-side. */
export function BankCallbackPage() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const complete = useCompleteLink(household.householdId);
  const [error, setError] = useState<string | null>(null);
  const ran = useRef(false);

  useEffect(() => {
    if (ran.current) return;
    ran.current = true;
    const code = params.get("code");
    const state = params.get("state");
    if (!code || !state) {
      setError(t("banks.callback_missing"));
      return;
    }
    complete
      .mutateAsync({ code, state })
      .then(() => {
        showToast(t("banks.link_success"), "success");
        navigate("/settings", { replace: true });
      })
      .catch((err) => {
        setError(apiErrorMessage(err, t));
      });
  }, [complete, navigate, params, t]);

  return (
    <Card>
      <CardBody>
        {error ? (
          <p className="text-sm text-red-600">{error}</p>
        ) : (
          <p className="text-gray-500 dark:text-gray-400">{t("banks.completing_link")}</p>
        )}
      </CardBody>
    </Card>
  );
}
