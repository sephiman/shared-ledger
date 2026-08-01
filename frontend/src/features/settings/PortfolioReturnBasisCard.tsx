import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useAuth } from "@/auth/AuthContext";
import { useUpdatePortfolioReturnBasis } from "@/api/settings";
import { apiErrorMessage } from "@/api/client";
import { Card, CardBody, CardHeader, FieldError, Label, Select } from "@/components/ui/primitives";

const OPTIONS = ["OPEN_COST", "NET_INVESTED", "TURNOVER"] as const;

/** Per-user base for the portfolio Realized % and Total return %. Purely visual: it changes only the
 *  percentage denominators, never the euro amounts, and only for this account. */
export function PortfolioReturnBasisCard() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const update = useUpdatePortfolioReturnBasis();
  const [error, setError] = useState<string | null>(null);
  const basis = user?.portfolioReturnBasis ?? "OPEN_COST";

  const change = (value: string) => {
    setError(null);
    update.mutate(value, { onError: (err) => setError(apiErrorMessage(err, t)) });
  };

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("settings.portfolio_return_basis_title")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("settings.portfolio_return_basis_description")}</p>
      </CardHeader>
      <CardBody className="space-y-3">
        <div>
          <Label htmlFor="portfolio-return-basis">{t("settings.portfolio_return_basis_label")}</Label>
          <Select
            id="portfolio-return-basis"
            className="w-full sm:w-auto"
            value={basis}
            disabled={update.isPending}
            onChange={(e) => change(e.target.value)}
          >
            {OPTIONS.map((o) => (
              <option key={o} value={o}>
                {t(`settings.portfolio_return_basis_option_${o}`)}
              </option>
            ))}
          </Select>
        </div>
        <ul className="space-y-2 text-xs text-gray-500 dark:text-gray-400">
          {OPTIONS.map((o) => (
            <li key={o}>
              <span className="font-medium text-gray-700 dark:text-gray-300">
                {t(`settings.portfolio_return_basis_option_${o}`)}:
              </span>{" "}
              {t(`settings.portfolio_return_basis_help_${o}`)}
            </li>
          ))}
        </ul>
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("settings.portfolio_return_basis_disclaimer")}</p>
        {error && <FieldError message={error} />}
      </CardBody>
    </Card>
  );
}
