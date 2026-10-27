import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import type { AiSuggestionSettings } from "@/api/aiSuggestions";
import type { Category } from "@/api/catalog";
import {
  useRuleGeneralisationStatus,
  useStartRuleGeneralisation,
  type RuleGeneralisationProposal,
  type RuleGeneralisationRun,
} from "@/api/ruleGeneralisations";
import { InfoTip } from "@/components/ui/InfoTip";
import { Button, Card, CardBody } from "@/components/ui/primitives";
import { GeneralisationProposalCard } from "./GeneralisationProposalCard";

/** "Suggest generalised rules": the model groups exact rules of one payee into one `contains` proposal; the
 *  server decides whether each is safe. Proposals live only in this view: dismissing or reloading drops them. */
export function RuleGeneralisationPanel({
  householdId,
  settings,
  categories,
}: {
  householdId: string;
  settings: AiSuggestionSettings;
  categories: Category[];
}) {
  const { t } = useTranslation();
  const { data: status } = useRuleGeneralisationStatus(householdId, true);
  const start = useStartRuleGeneralisation(householdId);
  const [watchedRunId, setWatchedRunId] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState<Set<string>>(new Set());

  const run = status?.run ?? null;
  const running = run?.status === "running";
  const tooFew = status !== undefined && status.eligibleRules < status.minRules;

  useEffect(() => {
    if (running && run) setWatchedRunId(run.id);
  }, [running, run]);

  const watched = run && run.id === watchedRunId ? run : null;
  const proposals = watched ? (status?.proposals ?? []).filter((p) => !dismissed.has(p.id)) : [];
  const dismiss = (id: string) => setDismissed((prev) => new Set(prev).add(id));

  return (
    <Card>
      <CardBody className="space-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="relative inline-flex items-center gap-1">
            <Button
              variant="secondary"
              disabled={running || start.isPending || tooFew || status === undefined}
              onClick={() =>
                start.mutate(undefined, {
                  onSuccess: (r) => {
                    setWatchedRunId(r.id);
                    setDismissed(new Set());
                  },
                })
              }
            >
              {t("banks.generalise")}
            </Button>
            <InfoTip label={t("banks.generalise_info_label")} openOnHover anchor="container" panelClassName="top-full">
              {t("banks.generalise_info", { model: settings.model, host: settings.providerHost })}
            </InfoTip>
          </span>
          {tooFew && status && (
            <span className="text-sm text-gray-500 dark:text-gray-400">
              {t("banks.generalise_too_few", { min: status.minRules, count: status.eligibleRules })}
            </span>
          )}
        </div>
        {watched && <RunLine run={watched} proposals={status?.proposals ?? null} expired={status?.proposalsExpired ?? false} />}
        {watched &&
          proposals.map((p: RuleGeneralisationProposal) => (
            <GeneralisationProposalCard
              key={p.id}
              householdId={householdId}
              runId={watched.id}
              proposal={p}
              categories={categories}
              onDone={() => dismiss(p.id)}
            />
          ))}
      </CardBody>
    </Card>
  );
}

function RunLine({
  run,
  proposals,
  expired,
}: {
  run: RuleGeneralisationRun;
  proposals: RuleGeneralisationProposal[] | null;
  expired: boolean;
}) {
  const { t } = useTranslation();
  const line = (text: string) => (
    <p role="status" className="text-sm text-gray-600 dark:text-gray-300">
      {text}
    </p>
  );
  if (run.status === "running") return line(t("banks.generalise_running", { count: run.rulesSent }));
  if (run.status !== "completed") return line(t("banks.generalise_failed"));
  if (expired) return line(t("banks.generalise_expired"));
  const collisions = (proposals ?? []).filter((p) => p.check.status === "conflict").length;
  return (
    <>
      {line(
        t("banks.generalise_result", {
          shown: run.proposalsShown,
          discarded: run.proposalsDiscarded,
          collisions,
        }),
      )}
      {run.rulesSent < run.rulesTotal &&
        line(t("banks.generalise_capped", { sent: run.rulesSent, total: run.rulesTotal }))}
    </>
  );
}
