import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { DataExportCard } from "./DataExportCard";
import { TransactionImportCard } from "./TransactionImportCard";
import { SnapshotImportCard } from "./SnapshotImportCard";
import { MovementImportCard } from "./MovementImportCard";
import { RecurringImportCard } from "./RecurringImportCard";
import { LoanImportCard } from "./LoanImportCard";
import { LoanPaymentImportCard } from "./LoanPaymentImportCard";
import { PortfolioImportCard } from "./PortfolioImportCard";
import { AmortizationImportCard, AssetImportCard, NamedLiabilityImportCard } from "./NamedImportCards";

export function DataImportPage() {
  const { t } = useTranslation();
  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">{t("import.title")}</h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("import.page_description")}</p>
        </div>
        <Link to="/settings" className="text-sm text-primary">{t("common.cancel")}</Link>
      </div>
      <DataExportCard />
      <TransactionImportCard />
      <SnapshotImportCard />
      <MovementImportCard />
      <RecurringImportCard />
      <LoanImportCard />
      <LoanPaymentImportCard />
      <PortfolioImportCard />
      <AssetImportCard />
      <NamedLiabilityImportCard />
      <AmortizationImportCard />
    </div>
  );
}
