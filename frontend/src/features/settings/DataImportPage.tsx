import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { TransactionImportCard } from "./TransactionImportCard";
import { SnapshotImportCard } from "./SnapshotImportCard";
import { MovementImportCard } from "./MovementImportCard";

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
      <TransactionImportCard />
      <SnapshotImportCard />
      <MovementImportCard />
    </div>
  );
}
