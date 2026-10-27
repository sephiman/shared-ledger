import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { apiErrorMessage } from "@/api/client";
import type { Category } from "@/api/catalog";
import {
  useAcceptGeneralisation,
  useValidateGeneralisation,
  type GeneralisationRequest,
  type ProposalCheck,
  type RuleGeneralisationProposal,
} from "@/api/ruleGeneralisations";
import { Button, Input, Label } from "@/components/ui/primitives";
import { categoryLabelByCode } from "@/lib/categoryLabel";

const VALIDATE_DELAY_MS = 400;

/** One proposal: the pattern can be edited, and every edit is re-checked by the server before Accept unlocks. */
export function GeneralisationProposalCard({
  householdId,
  runId,
  proposal,
  categories,
  onDone,
}: {
  householdId: string;
  runId: string;
  proposal: RuleGeneralisationProposal;
  categories: Category[];
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const validate = useValidateGeneralisation(householdId);
  const accept = useAcceptGeneralisation(householdId);
  const [pattern, setPattern] = useState(proposal.pattern);
  // The verdict and the pattern it was given for; an edit makes it stale until the server answers again.
  const [verdict, setVerdict] = useState<{ pattern: string; check: ProposalCheck }>({
    pattern: proposal.pattern,
    check: proposal.check,
  });
  const [error, setError] = useState<string | null>(null);
  const validateRef = useRef(validate.mutate);
  validateRef.current = validate.mutate;

  // A refetched proposal (another accept, a rule edit) carries a fresh verdict for the original pattern.
  useEffect(() => {
    setVerdict((prev) => (prev.pattern === proposal.pattern ? { pattern: proposal.pattern, check: proposal.check } : prev));
  }, [proposal.pattern, proposal.check]);

  const request = (p: string): GeneralisationRequest => ({
    pattern: p,
    field: proposal.field,
    direction: proposal.direction,
    categoryCode: proposal.categoryCode,
    absorbedRuleIds: proposal.absorbed.map((r) => r.id),
    runId,
  });

  useEffect(() => {
    if (pattern === verdict.pattern || pattern.trim() === "") return;
    const timer = setTimeout(() => {
      validateRef.current(request(pattern), { onSuccess: (check) => setVerdict({ pattern, check }) });
    }, VALIDATE_DELAY_MS);
    return () => clearTimeout(timer);
    // request() only reads props that identify the proposal; the pattern is what re-runs this.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pattern, verdict.pattern]);

  const checking = pattern !== verdict.pattern;
  const check = verdict.check;
  const canAccept = !checking && check.status === "ok" && !accept.isPending;

  const onAccept = () => {
    setError(null);
    accept.mutate(request(pattern), {
      onSuccess: onDone,
      onError: (err) => setError(apiErrorMessage(err, t)),
    });
  };

  return (
    <article
      aria-label={t("banks.generalise_card_label", { pattern: proposal.pattern })}
      className="space-y-3 rounded-md border border-border bg-surface p-3"
    >
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-end">
        <div>
          <Label htmlFor={`pattern-${proposal.id}`}>{t("banks.generalise_pattern")}</Label>
          <Input id={`pattern-${proposal.id}`} value={pattern} onChange={(e) => setPattern(e.target.value)} />
        </div>
        <p className="text-sm">
          {t(`banks.field_${proposal.field}`)} {t("banks.op_contains")} →{" "}
          <span className="font-medium">{categoryLabelByCode(proposal.categoryCode, categories, t)}</span> (
          {t(`common.${proposal.direction}`)})
        </p>
      </div>

      <div className="text-sm">
        <p className="text-gray-600 dark:text-gray-300">{t("banks.generalise_replaces", { count: proposal.absorbed.length })}</p>
        <ul className="mt-1 flex flex-wrap gap-1">
          {proposal.absorbed.map((r) => (
            <li key={r.id}>
              <code className="break-all rounded bg-gray-100 px-1 dark:bg-raised">{r.matchValue}</code>
            </li>
          ))}
        </ul>
      </div>

      <Verdict checking={checking} check={check} categories={categories} />
      {error && (
        <p role="alert" className="text-sm text-red-700 dark:text-red-300">
          {error}
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        <Button disabled={!canAccept} onClick={onAccept}>
          {t("banks.generalise_accept")}
        </Button>
        <Button variant="secondary" onClick={onDone}>
          {t("banks.generalise_dismiss")}
        </Button>
      </div>
    </article>
  );
}

function Verdict({ checking, check, categories }: { checking: boolean; check: ProposalCheck; categories: Category[] }) {
  const { t } = useTranslation();
  if (checking) {
    return (
      <p role="status" className="text-sm text-gray-500 dark:text-gray-400">
        {t("banks.generalise_checking")}
      </p>
    );
  }
  if (check.status === "ok") {
    return (
      <p role="status" className="text-sm text-green-700 dark:text-green-400">
        {t("banks.generalise_ok")}
      </p>
    );
  }
  if (check.status === "invalid") {
    return (
      <p role="status" className="text-sm text-red-700 dark:text-red-300">
        {t(`banks.generalise_problem_${check.problem ?? "unknown_rule"}`)}
      </p>
    );
  }
  return (
    <div role="status" className="space-y-1 rounded-md border border-amber-300 bg-amber-50 p-2 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950/40 dark:text-amber-200">
      {check.collisionCount > 0 && (
        <p>
          {t("banks.generalise_collisions", { count: check.collisionCount })}{" "}
          <span className="break-words font-medium">{check.collisionExamples.join(", ")}</span>
        </p>
      )}
      {check.changeCount > 0 && (
        <div>
          <p>{t("banks.generalise_changes", { count: check.changeCount })}</p>
          <ul className="mt-1 space-y-0.5">
            {check.changeExamples.map((c) => (
              <li key={`${c.counterparty ?? ""}|${c.description ?? ""}`} className="break-words">
                <span className="font-medium">{[c.counterparty, c.description].filter(Boolean).join(" · ")}</span>:{" "}
                {categoryLabelByCode(c.fromCategory, categories, t)} →{" "}
                {c.toCategory ? categoryLabelByCode(c.toCategory, categories, t) : t("banks.generalise_no_category")}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
