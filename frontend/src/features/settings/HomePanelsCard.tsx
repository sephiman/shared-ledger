import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useAuth } from "@/auth/AuthContext";
import { useUpdateHomePanels } from "@/api/settings";
import { apiErrorMessage } from "@/api/client";
import { Card, CardBody, CardHeader, FieldError, Toggle } from "@/components/ui/primitives";
import { HOME_PANELS, isPanelVisible, setPanelVisible, type HomePanelId } from "@/lib/homePanels";

/** Per-user visibility toggles for the six top Home panels. The preference only hides a panel that would
 *  otherwise show; data-dependent tiles still render nothing while they have no data. */
export function HomePanelsCard() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const update = useUpdateHomePanels();
  const [error, setError] = useState<string | null>(null);

  const hidden = user?.hiddenHomePanels;

  const toggle = (id: HomePanelId, visible: boolean) => {
    setError(null);
    update.mutate(setPanelVisible(hidden, id, visible), {
      onError: (err) => setError(apiErrorMessage(err, t)),
    });
  };

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("settings.home_panels_title")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("settings.home_panels_description")}</p>
      </CardHeader>
      <CardBody>
        <div className="divide-y divide-border dark:divide-gray-700">
          {HOME_PANELS.map((p) => (
            <Toggle
              key={p.id}
              checked={isPanelVisible(hidden, p.id)}
              disabled={update.isPending}
              onChange={(v) => toggle(p.id, v)}
              label={
                <span className="inline-flex items-center gap-1.5">
                  {t(p.labelKey)}
                  {p.dataDependent && (
                    <span
                      className="cursor-help text-gray-400 dark:text-gray-500"
                      title={t("settings.home_panel_data_dependent")}
                      aria-label={t("settings.home_panel_data_dependent")}
                    >
                      ⓘ
                    </span>
                  )}
                </span>
              }
            />
          ))}
        </div>
        <p className="mt-3 text-xs text-gray-500 dark:text-gray-400">{t("settings.home_panels_disclaimer")}</p>
        {error && <FieldError message={error} />}
      </CardBody>
    </Card>
  );
}
