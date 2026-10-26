import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import {
  useAiSuggestionStatus,
  useStartAiSuggestions,
  type AiSuggestionRun,
  type AiSuggestionSettings,
} from "@/api/aiSuggestions";
import { Button } from "@/components/ui/primitives";
import { InfoTip } from "@/components/ui/InfoTip";

/** "Suggest with AI" next to Apply rules: sends every unsuggested pending item, whatever the inbox filters
 *  show, and reports progress while the server works through the batches. */
export function AiSuggestButton({ householdId, settings }: { householdId: string; settings: AiSuggestionSettings }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const { data: status } = useAiSuggestionStatus(householdId, true);
  const start = useStartAiSuggestions(householdId);
  // The run this view started or saw running; its result line stays until the next one.
  const [watchedRunId, setWatchedRunId] = useState<string | null>(null);

  const run = status?.run ?? null;
  const running = run?.status === "running";

  useEffect(() => {
    if (running && run) setWatchedRunId(run.id);
  }, [running, run]);

  // Suggestions land on rows as each batch finishes; refresh the list once the run is over.
  useEffect(() => {
    if (run && run.id === watchedRunId && !running) void qc.invalidateQueries({ queryKey: ["banks", householdId, "pending"] });
  }, [run, running, watchedRunId, householdId, qc]);

  const watched = run && run.id === watchedRunId ? run : null;

  return (
    <>
      {/* The panel hangs off this group: from its start on phones, where the group wraps to the left edge,
          and from its end once the toolbar keeps it on the right. */}
      <span className="relative inline-flex items-center gap-1">
        <Button
          variant="secondary"
          disabled={running || start.isPending || (status?.candidates ?? 0) === 0}
          onClick={() => start.mutate(undefined, { onSuccess: (r) => setWatchedRunId(r.id) })}
        >
          {t("banks.ai_suggest")}
        </Button>
        <InfoTip label={t("banks.ai_suggest_info_label")} openOnHover anchor="container" panelClassName="top-full sm:left-auto sm:right-0">
          {t("banks.ai_suggest_info", { model: settings.model, host: settings.providerHost })}
        </InfoTip>
      </span>
      {watched && <AiRunLine run={watched} />}
    </>
  );
}

function AiRunLine({ run }: { run: AiSuggestionRun }) {
  const { t } = useTranslation();
  if (run.status === "running") {
    return (
      <p role="status" className="basis-full text-sm text-gray-600 dark:text-gray-300">
        {t("banks.ai_progress", { done: run.batchesDone, total: run.batchesTotal })}
      </p>
    );
  }
  const counts = { stored: run.suggestionsStored, unsuggested: run.itemsUnsuggested, failed: run.batchesFailed };
  return (
    <p role="status" className="basis-full text-sm text-gray-600 dark:text-gray-300">
      {run.status === "completed" ? t("banks.ai_result", counts) : t("banks.ai_result_stopped", counts)}
    </p>
  );
}
