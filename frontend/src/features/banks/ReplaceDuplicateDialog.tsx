import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { apiErrorMessage } from "@/api/client";
import {
  usePendingDuplicateCandidates,
  useReplaceWithTransaction,
  type Direction,
  type DuplicateCandidate,
  type PendingMovement,
} from "@/api/banks";
import type { Category } from "@/api/catalog";
import { categoryLabel, categoryLabelByCode } from "@/lib/categoryLabel";
import { cn } from "@/lib/cn";
import { formatDate } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { showToast } from "@/lib/toastBus";
import { Badge, Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import {
  buildComparison,
  categoryAfterDirectionChange,
  selectableCandidates,
  type CompareField,
  type ReplaceDraft,
} from "./replaceCompare";

interface Props {
  open: boolean;
  householdId: string;
  movement: PendingMovement;
  categories: Category[];
  currency: string;
  locale: string;
  /** The description as edited in the inbox row. */
  description: string;
  onClose: () => void;
  /** The row's normal confirm, offered when there's no longer anything to replace. */
  onFallbackConfirm: () => void;
  /** False while the row has no category chosen — a normal confirm would be refused. */
  canFallbackConfirm: boolean;
}

const FIELD_LABEL_KEY: Record<CompareField, string> = {
  date: "common.date",
  amount: "common.amount",
  direction: "common.direction",
  category: "common.category",
  description: "common.description",
  source: "banks.source",
};

/** Compares the two records field by field and, on confirm, updates the existing transaction in place
 *  instead of creating a second one. Category, direction and description stay editable. */
export function ReplaceDuplicateDialog({
  open, householdId, movement, categories, currency, locale, description, onClose, onFallbackConfirm, canFallbackConfirm,
}: Props) {
  const { t, i18n } = useTranslation();
  const { data: candidates, isLoading } = usePendingDuplicateCandidates(householdId, movement.id, open);
  const replace = useReplaceWithTransaction(householdId);

  const [transactionId, setTransactionId] = useState<string | null>(null);
  const [draft, setDraft] = useState<ReplaceDraft>({ categoryCode: "", direction: movement.direction, description: "" });
  const [submitError, setSubmitError] = useState<string | null>(null);

  const selectable = selectableCandidates(candidates ?? []);
  const selected = selectable.find((c) => c.transactionId === transactionId) ?? null;

  // The draft mirrors the targeted transaction: its category and direction, plus the inbox row's description.
  const seedFrom = (candidate: DuplicateCandidate) => {
    setTransactionId(candidate.transactionId);
    setDraft({
      categoryCode: candidate.categoryCode,
      direction: candidate.direction,
      description,
    });
  };

  // Only an unambiguous match is auto-selected, so Replace never acts on a guess.
  useEffect(() => {
    if (!open) return;
    setSubmitError(null);
    if (selectable.length === 1) {
      if (transactionId !== selectable[0].transactionId) seedFrom(selectable[0]);
    } else if (transactionId && !selectable.some((c) => c.transactionId === transactionId)) {
      setTransactionId(null);
    }
  }, [open, candidates]);

  // Escape dismisses without writing anything, like the overlay click and Cancel.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  const setDirection = (direction: Direction) =>
    setDraft((prev) => ({
      ...prev,
      direction,
      categoryCode: categoryAfterDirectionChange(prev.categoryCode, direction, categories),
    }));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selected) return;
    try {
      await replace.mutateAsync({
        id: movement.id,
        input: {
          transactionId: selected.transactionId,
          categoryCode: draft.categoryCode,
          direction: draft.direction,
          description: draft.description,
        },
      });
      showToast(t("banks.replaced_toast"), "success");
      onClose();
    } catch (err) {
      setSubmitError(apiErrorMessage(err, t));
    }
  };

  const rows = selected
    ? buildComparison(selected, movement, draft, {
        date: (iso) => formatDate(iso, i18n.language),
        money: (amount) => formatMoney(amount, currency, locale),
        direction: (d) => t(`common.${d}`),
        category: (code) => categoryLabelByCode(code, categories, t),
        manualSource: t("banks.source_manual"),
        empty: "—",
      })
    : [];

  const categoryOptions = categories
    .filter((c) => c.kind === draft.direction)
    .sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t)));
  const existingDescription = selected?.description?.trim() ?? "";

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/40 px-2 py-6 sm:items-center sm:px-4 sm:py-8"
      role="dialog"
      aria-modal="true"
      onClick={onClose}
    >
      <Card className="w-full max-w-2xl" onClick={(e) => e.stopPropagation()}>
        <CardHeader>
          <p className="font-medium">{t("banks.replace_title")}</p>
          {/* The hint describes the comparison — out of place in the loading and fallback states. */}
          {selectable.length > 0 && (
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("banks.replace_hint")}</p>
          )}
        </CardHeader>
        <CardBody>
          {isLoading ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
          ) : selectable.length === 0 ? (
            // Nothing to replace, so offer the normal confirm rather than a dead dialog.
            <div className="space-y-3">
              <p className="text-sm text-gray-600 dark:text-gray-300">{t("banks.no_replaceable_match")}</p>
              {(candidates ?? []).filter((c) => c.bankLinked).map((c) => (
                <p key={c.transactionId} className="text-xs text-gray-500 dark:text-gray-400">
                  {formatDate(c.occurrenceDate, i18n.language)} · {formatMoney(c.amount, currency, locale)} —{" "}
                  {t("banks.candidate_bank_linked")}
                </p>
              ))}
              {!canFallbackConfirm && (
                <p className="text-xs text-amber-600 dark:text-amber-400">{t("banks.fallback_needs_category")}</p>
              )}
              <div className="flex justify-end gap-2 pt-1">
                <Button variant="ghost" onClick={onClose}>{t("common.cancel")}</Button>
                <Button
                  disabled={!canFallbackConfirm}
                  onClick={() => { onFallbackConfirm(); onClose(); }}
                >
                  {t("banks.confirm")}
                </Button>
              </div>
            </div>
          ) : (
            <form noValidate onSubmit={submit} className="space-y-4">
              {/* Ambiguous match: the target must be chosen explicitly. */}
              {selectable.length > 1 && (
                <fieldset className="space-y-2">
                  <legend className="mb-1 text-sm font-medium text-gray-700 dark:text-gray-300">
                    {t("banks.pick_candidate", { count: selectable.length })}
                  </legend>
                  {(candidates ?? []).map((c) => (
                    <label
                      key={c.transactionId}
                      className={cn(
                        "flex items-start gap-2 rounded-md border border-border p-2 text-sm",
                        c.bankLinked ? "opacity-60" : "cursor-pointer hover:bg-gray-50 dark:hover:bg-row-hover/40",
                        c.transactionId === transactionId && "border-primary/60 bg-sky-50 dark:bg-sky-900/20",
                      )}
                    >
                      <input
                        type="radio"
                        name="replace-candidate"
                        className="mt-1"
                        disabled={c.bankLinked}
                        checked={c.transactionId === transactionId}
                        onChange={() => seedFrom(c)}
                      />
                      <span className="min-w-0 flex-1">
                        <span className="flex flex-wrap items-center gap-x-2">
                          <span className="font-medium">{formatDate(c.occurrenceDate, i18n.language)}</span>
                          <span>{formatMoney(c.amount, currency, locale)}</span>
                          <Badge tone="neutral">{categoryLabelByCode(c.categoryCode, categories, t)}</Badge>
                          {c.bankLinked && <Badge tone="amber">{t("banks.candidate_bank_linked")}</Badge>}
                        </span>
                        {c.description && (
                          <span className="mt-0.5 block break-words text-xs text-gray-500 dark:text-gray-400">
                            {c.description}
                          </span>
                        )}
                      </span>
                    </label>
                  ))}
                </fieldset>
              )}

              {!selected ? (
                <p className="text-sm text-gray-500 dark:text-gray-400">{t("banks.pick_candidate_hint")}</p>
              ) : (
                // A fragment, so the form's space-y-4 still applies to these as DOM children.
                <>
                  {/* Side-by-side on tablet+, stacked labelled lines on phones. */}
                  <div className="overflow-hidden rounded-md border border-border">
                    <div className="hidden border-b border-border bg-gray-50 text-xs font-medium text-gray-500 dark:bg-surface/60 dark:text-gray-400 sm:grid sm:grid-cols-[7rem_1fr_1fr]">
                      <span className="px-3 py-2" />
                      <span className="px-3 py-2">{t("banks.existing_transaction")}</span>
                      <span className="px-3 py-2">{t("banks.incoming_movement")}</span>
                    </div>
                    <dl className="divide-y divide-border">
                      {rows.map((r) => (
                        <div
                          key={r.field}
                          className={cn(
                            "px-3 py-2 text-sm sm:grid sm:grid-cols-[7rem_1fr_1fr] sm:gap-x-3",
                            r.changed && "bg-amber-50 dark:bg-amber-900/20",
                          )}
                        >
                          <dt className="text-xs font-medium text-gray-500 dark:text-gray-400 sm:text-sm sm:font-normal">
                            {t(FIELD_LABEL_KEY[r.field])}
                          </dt>
                          <dd className="break-words text-gray-600 dark:text-gray-300">
                            <span className="text-gray-400 sm:hidden">{t("banks.existing_transaction")}: </span>
                            {r.existing}
                          </dd>
                          <dd
                            className={cn(
                              "break-words",
                              r.changed
                                ? "font-medium text-amber-800 dark:text-amber-200"
                                : "text-gray-600 dark:text-gray-300",
                            )}
                          >
                            <span className="font-normal text-gray-400 sm:hidden">{t("banks.incoming_movement")}: </span>
                            {r.incoming}
                          </dd>
                        </div>
                      ))}
                    </dl>
                  </div>

                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                    <div>
                      <Label>{t("common.direction")}</Label>
                      <Select value={draft.direction} onChange={(e) => setDirection(e.target.value as Direction)}>
                        <option value="expense">{t("common.expense")}</option>
                        <option value="income">{t("common.income")}</option>
                      </Select>
                    </div>
                    <div>
                      <Label>{t("common.category")}</Label>
                      <Select
                        value={draft.categoryCode}
                        onChange={(e) => setDraft((prev) => ({ ...prev, categoryCode: e.target.value }))}
                      >
                        <option value="">{t("banks.uncategorized")}</option>
                        {categoryOptions.map((c) => (
                          <option key={c.code} value={c.code}>{categoryLabel(c, t)}</option>
                        ))}
                      </Select>
                    </div>
                  </div>

                  <div>
                    <Label>{t("common.description")}</Label>
                    <Input
                      value={draft.description}
                      onChange={(e) => setDraft((prev) => ({ ...prev, description: e.target.value }))}
                      placeholder={t("common.description")}
                    />
                    {existingDescription && existingDescription !== draft.description.trim() && (
                      <button
                        type="button"
                        className="mt-1 text-xs text-primary underline"
                        onClick={() => setDraft((prev) => ({ ...prev, description: existingDescription }))}
                      >
                        {t("banks.use_existing_description")}
                      </button>
                    )}
                  </div>
                </>
              )}

              {submitError && <FieldError message={submitError} />}

              <div className="flex justify-end gap-2 pt-1">
                <Button type="button" variant="ghost" onClick={onClose}>{t("common.cancel")}</Button>
                <Button type="submit" disabled={!selected || !draft.categoryCode || replace.isPending}>
                  {t("banks.replace_submit")}
                </Button>
              </div>
            </form>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
