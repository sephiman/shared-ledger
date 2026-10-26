import { useState } from "react";
import { useTranslation } from "react-i18next";
import { apiErrorMessage } from "@/api/client";
import { useAiSuggestionSettings, useUpdateAiSuggestionSettings } from "@/api/aiSuggestions";
import { Card, CardBody, CardHeader, FieldError } from "@/components/ui/primitives";

/** Owner-only opt-in. Rendered only when the instance has an AI provider; the help text names exactly what
 *  leaves the system and where it goes, so the owner decides with that in front of them. */
export function AiSuggestionsCard({ householdId }: { householdId: string }) {
  const { t } = useTranslation();
  const { data: settings } = useAiSuggestionSettings(householdId);
  const update = useUpdateAiSuggestionSettings(householdId);
  // The value being saved, set in the same event so the box never snaps back while the request is in flight.
  const [saving, setSaving] = useState<boolean | null>(null);

  if (!settings) return null;
  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("settings.ai_suggestions_title")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          {t("settings.ai_suggestions_help", { model: settings.model, host: settings.providerHost })}
        </p>
      </CardHeader>
      <CardBody className="space-y-2">
        <label className="inline-flex items-center gap-2">
          <input
            type="checkbox"
            checked={saving ?? settings.enabled}
            disabled={saving !== null}
            onChange={(e) => {
              setSaving(e.target.checked);
              update.mutate(e.target.checked, { onSettled: () => setSaving(null) });
            }}
          />
          {t("settings.ai_suggestions_enable")}
        </label>
        <FieldError message={update.error ? apiErrorMessage(update.error, t) : null} />
      </CardBody>
    </Card>
  );
}
