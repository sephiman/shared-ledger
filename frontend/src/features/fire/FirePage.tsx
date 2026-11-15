import { type ComponentProps, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useAiSuggestionsOn } from "@/api/aiSuggestions";
import { useFireHistory, useFireLevers, useFirePreview, useFireProjection, useFireSettings, useUpdateFireSettings, type FirePreviewRequest, type FireProjection, type FireSettings } from "@/api/fire";
import { useAssetClasses } from "@/api/catalog";
import { apiErrorMessage } from "@/api/client";
import { TabBar } from "@/components/ui/TabBar";
import { FireChart } from "./FireChart";
import { FireHeroStrip } from "./FireHeroStrip";
import { FireTiersCard } from "./FireTiersCard";
import { FireHistoryCard } from "./FireHistoryCard";
import { FireAttributionCard } from "./FireAttributionCard";
import { FireScenarioTable } from "./FireScenarioTable";
import { FireSettingsCard } from "./FireSettingsCard";
import { fireFormErrors, type FireFormErrors } from "./fireFormValidation";
import { useFireSettingsSource } from "./useFireSettingsSource";
import { unsavedSections } from "./fireUnsavedSections";
import { FireWhatIfBar, buildPreviewRequest, draftFromPreview, initialWhatIfDraft, type WhatIfDraft } from "./FireWhatIfBar";
import { FireLeversSection } from "./FireLeversSection";
import { FireAiAnalysisTab } from "./FireAiAnalysisTab";
import { resolveReferenceScenarioIndex, scenarioDisplayName } from "./fireLabels";

const PHOTO_ANCHOR_ID = "fire-photo";

export function FirePage() {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const isOwner = household.role === "owner";
  const { data: settings } = useFireSettings(household.householdId);
  const projectionQuery = useFireProjection(household.householdId);
  const projection = projectionQuery.data;
  const { data: assetClasses = [] } = useAssetClasses();
  const update = useUpdateFireSettings(household.householdId);
  const preview = useFirePreview(household.householdId);
  // Fetched only once the photo is on screen, so the photo never waits for it.
  const history = useFireHistory(household.householdId, Boolean(projection));
  const levers = useFireLevers(household.householdId, Boolean(projection));

  const [form, setForm] = useState<FireSettings | null>(null);
  const [errors, setErrors] = useState<FireFormErrors>({});
  const [whatIfDraft, setWhatIfDraft] = useState<WhatIfDraft | null>(null);
  const ai = useAiSuggestionsOn(household.householdId);
  const [searchParams, setSearchParams] = useSearchParams();
  const rawTab = searchParams.get("tab");
  const tab: "projection" | "settings" | "ai_analysis" =
    rawTab === "ai_analysis" && ai ? "ai_analysis" : rawTab === "settings" ? "settings" : "projection";
  const setTab = (next: "projection" | "settings" | "ai_analysis") => {
    setSearchParams(next === "projection" ? {} : { tab: next }, { replace: true });
  };
  const [whatIfOpen, setWhatIfOpen] = useState(false);
  const [pendingScrollToPhoto, setPendingScrollToPhoto] = useState(false);

  useEffect(() => {
    if (tab !== "projection" || !pendingScrollToPhoto) return;
    setPendingScrollToPhoto(false);
    document.getElementById(PHOTO_ANCHOR_ID)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [tab, pendingScrollToPhoto]);

  useEffect(() => {
    if (settings) setForm(settings);
  }, [settings]);

  useEffect(() => {
    if (projection) {
      setWhatIfDraft(initialWhatIfDraft(projection));
      preview.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projection]);

  useEffect(() => {
    if (!projection || !whatIfDraft) return;
    const req = buildPreviewRequest(whatIfDraft, projection);
    if (!req) {
      if (preview.data) preview.reset();
      return;
    }
    const timer = window.setTimeout(() => {
      preview.mutate(req);
    }, 250);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [whatIfDraft, projection]);

  const activeProjection = preview.data ?? projection;
  const isWhatIfActive = Boolean(projection && whatIfDraft && buildPreviewRequest(whatIfDraft, projection));

  function handleResetWhatIf() {
    if (!projection) return;
    setWhatIfDraft(initialWhatIfDraft(projection));
    preview.reset();
  }

  function handleBackToPlan() {
    handleResetWhatIf();
    setWhatIfOpen(false);
  }

  function handleApplyWhatIf() {
    if (!form || !projection || !whatIfDraft) return;
    const baseContrib = projection.contributions.activeMonthly ?? projection.settings.monthlyContribution;
    const contribChanged = Number(whatIfDraft.monthlyContribution) !== Number(baseContrib);
    const spendChanged =
      Number(whatIfDraft.monthlySpending) > 0 &&
      Number(whatIfDraft.monthlySpending) !== Number(projection.spending.totalMonthly);
    const essentialChanged =
      Number(whatIfDraft.monthlyEssentialSpending) > 0 &&
      Number(whatIfDraft.monthlyEssentialSpending) !== Number(projection.spending.essentialMonthly);
    const nextForm: FireSettings = {
      ...form,
      targetYear: Number(whatIfDraft.targetYear) || form.targetYear,
      safeWithdrawalRatePct: whatIfDraft.safeWithdrawalRatePct || form.safeWithdrawalRatePct,
      ...(contribChanged ? { contributionMode: "manual", monthlyContribution: whatIfDraft.monthlyContribution } : {}),
      ...(spendChanged ? { totalSpendingMode: "manual", manualTotalSpending: whatIfDraft.monthlySpending } : {}),
      ...(essentialChanged
        ? { essentialSpendingMode: "manual", manualEssentialSpending: whatIfDraft.monthlyEssentialSpending }
        : {}),
      ...(whatIfDraft.cashFlowEvents != null ? { cashFlowEvents: whatIfDraft.cashFlowEvents } : {}),
    };
    setForm(nextForm);
    update.mutate(nextForm);
  }

  /** A lever row (or question) opens the what-if with exactly the values it was computed with. */
  function handleOpenLever(leverPreview: FirePreviewRequest) {
    if (!projection) return;
    setWhatIfDraft(draftFromPreview(leverPreview, projection));
    setWhatIfOpen(true);
    // The page scrolls inside the layout, not the window: bring the photo (and its what-if) into view.
    document.getElementById(PHOTO_ANCHOR_ID)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function onChange(next: FireSettings, clear?: (keyof FireFormErrors)[]) {
    setForm(next);
    if (update.isSuccess || update.isError) update.reset();
    if (clear?.length) {
      setErrors((prev) => {
        const cleaned = { ...prev };
        for (const key of clear) delete cleaned[key];
        return cleaned;
      });
    }
  }

  function onScenarioErrorClear(idx: number, field: "meanPercent" | "stdDevPercent") {
    setErrors((prev) => {
      if (!prev.scenarios?.[idx]?.[field]) return prev;
      const scenarios = [...(prev.scenarios ?? [])];
      scenarios[idx] = { ...scenarios[idx], [field]: undefined };
      return { ...prev, scenarios };
    });
  }

  function validateAndSave() {
    if (!form) return;
    const next = fireFormErrors(form, projection?.startYear, t);
    if (Object.keys(next).length > 0) {
      setErrors(next);
      return;
    }
    setErrors({});
    update.mutate(form);
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold">{t("fire.title")}</h1>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("fire.description")}</p>
      </div>

      <TabBar
        items={[
          { value: "projection", label: t("fire.tab_projection") },
          { value: "settings", label: t("nav.settings") },
          ...(ai ? [{ value: "ai_analysis", label: t("fire.tab_ai_analysis") }] : []),
        ]}
        value={tab}
        onChange={(v) => setTab(v as "projection" | "settings" | "ai_analysis")}
        ariaLabel={t("fire.title")}
      />

      {tab === "projection" && (
        <>
          {activeProjection && (
            <div id={PHOTO_ANCHOR_ID} className="scroll-mt-4">
            <FireHeroStrip
              projection={activeProjection}
              baselineProjection={isWhatIfActive ? projection : undefined}
              currency={household.currency}
              whatIfOpen={whatIfOpen || isWhatIfActive}
              isWhatIfActive={isWhatIfActive}
              onToggleWhatIf={() => setWhatIfOpen((v) => !v)}
              onBackToPlan={handleBackToPlan}
              whatIfSlot={
                projection && whatIfDraft && (whatIfOpen || isWhatIfActive) ? (
                  <FireWhatIfBar
                    householdId={household.householdId}
                    baseProjection={projection}
                    draft={whatIfDraft}
                    isActive={isWhatIfActive}
                    isLoading={preview.isPending}
                    isOwner={isOwner}
                    currency={household.currency}
                    onChange={setWhatIfDraft}
                    onReset={handleResetWhatIf}
                    onApply={handleApplyWhatIf}
                  />
                ) : undefined
              }
            />
            </div>
          )}
          {activeProjection && (
            <FireTiersCard
              projection={activeProjection}
              baselineProjection={isWhatIfActive ? projection : undefined}
              currency={household.currency}
            />
          )}
          {projection && (
            <FireHistoryCard
              history={history.data}
              isLoading={history.isLoading}
              isError={history.isError}
              projection={projection}
              currency={household.currency}
            />
          )}
          {history.data && (
            <FireAttributionCard
              householdId={household.householdId}
              history={history.data}
              currency={household.currency}
              noSpendingSince={projection?.spending.noSpendingSince ?? null}
            />
          )}
          {projection && (
            <FireLeversSection
              levers={levers.data}
              scenarioName={scenarioDisplayName(projection.scenarios, resolveReferenceScenarioIndex(projection), t)}
              isLoading={levers.isLoading}
              isError={levers.isError}
              currency={household.currency}
              onOpen={handleOpenLever}
            />
          )}
          {activeProjection && (
            <FireScenarioTable
              projection={activeProjection}
              baselineProjection={isWhatIfActive ? projection : undefined}
              currency={household.currency}
            />
          )}
          {activeProjection && (
            <FireChart
              projection={activeProjection}
              baselineProjection={isWhatIfActive ? projection : undefined}
              currency={household.currency}
            />
          )}
        </>
      )}

      {tab === "settings" && form && (
        <FireSettingsTab
          householdId={household.householdId}
          form={form}
          savedSettings={settings}
          savedProjection={projectionQuery}
          errors={errors}
          isOwner={isOwner}
          saving={update.isPending}
          saved={update.isSuccess}
          serverError={update.isError ? apiErrorMessage(update.error, t) : null}
          assetClasses={assetClasses}
          currency={household.currency}
          onChange={onChange}
          onScenarioErrorClear={onScenarioErrorClear}
          onSave={validateAndSave}
        />
      )}

      {tab === "ai_analysis" && ai && (
        <FireAiAnalysisTab
          householdId={household.householdId}
          currency={household.currency}
          projection={projection}
          onPreviewWhatIf={(previewReq) => {
            if (!projection) return;
            setWhatIfDraft(draftFromPreview(previewReq, projection));
            setWhatIfOpen(true);
            setPendingScrollToPhoto(true);
            setTab("projection");
          }}
        />
      )}
    </div>
  );
}

type FireSettingsTabProps = Omit<ComponentProps<typeof FireSettingsCard>, "projection" | "source" | "unsaved"> & {
  savedSettings: FireSettings | undefined;
  savedProjection: { data: FireProjection | undefined; isFetching: boolean };
};

/** The settings card fed from the projection of the form as it stands, not of what was last saved. */
function FireSettingsTab({ savedSettings, savedProjection, ...card }: FireSettingsTabProps) {
  const source = useFireSettingsSource(card.householdId, card.form, savedSettings, savedProjection);
  return (
    <FireSettingsCard
      {...card}
      projection={savedProjection.data}
      source={source}
      unsaved={unsavedSections(card.form, savedSettings)}
    />
  );
}
