import { useState } from "react";
import { useTranslation } from "react-i18next";
import type { FireLevers, FirePreviewRequest } from "@/api/fire";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { FireLeversCard, leversSummary } from "./FireLeversCard";

/** "What moves your year most": the result in the header, the table when opened. The levers are fetched in the
 *  background once the photo is on screen. */
export function FireLeversSection({
  levers,
  scenarioName,
  isLoading,
  isError,
  currency,
  onOpen,
}: {
  levers: FireLevers | undefined;
  scenarioName: string;
  isLoading: boolean;
  isError: boolean;
  currency: string;
  onOpen: (preview: FirePreviewRequest) => void;
}) {
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const summary = levers ? leversSummary(levers, currency, i18n.language, t) : t("fire.levers_summary_pending");
  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="font-medium">{t("fire.levers_title")}</p>
          <button
            type="button"
            aria-expanded={open}
            onClick={() => setOpen((v) => !v)}
            className="text-xs text-sky-600 hover:underline dark:text-sky-400"
          >
            {open ? t("fire.section_hide") : t("fire.levers_show")}
          </button>
        </div>
        <p className="mt-1 text-sm text-gray-700 dark:text-gray-300">{summary}</p>
      </CardHeader>
      {open && (
        <CardBody>
          <FireLeversCard levers={levers} scenarioName={scenarioName} isLoading={isLoading} isError={isError} currency={currency} onOpen={onOpen} />
        </CardBody>
      )}
    </Card>
  );
}
