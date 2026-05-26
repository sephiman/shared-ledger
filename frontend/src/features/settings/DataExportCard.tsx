import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";

export function DataExportCard() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("export.title")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("export.description")}</p>
      </CardHeader>
      <CardBody>
        <a
          href={`/api/households/${household.householdId}/export-all.zip`}
          download
          className="inline-flex items-center justify-center rounded-md border border-border bg-white px-4 py-2 text-sm font-medium text-gray-900 hover:bg-gray-50 dark:bg-gray-700 dark:text-gray-100 dark:border-gray-600 dark:hover:bg-gray-600"
        >
          {t("export.download_all")}
        </a>
      </CardBody>
    </Card>
  );
}
