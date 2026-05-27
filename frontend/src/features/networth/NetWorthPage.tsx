import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { TabBar } from "@/components/ui/TabBar";
import { SnapshotsTab } from "./SnapshotsTab";
import { EvolutionTab } from "./EvolutionTab";
import { AllocationTab } from "./AllocationTab";
import { MovementsTab } from "./MovementsTab";
import { LiabilitiesTab } from "./LiabilitiesTab";
import { LoansTab } from "../loans/LoansTab";

type Tab = "snapshots" | "evolution" | "allocation" | "movements" | "liabilities" | "loans";

const TABS: Tab[] = ["snapshots", "evolution", "allocation", "movements", "liabilities", "loans"];

export function NetWorthPage() {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const initialTab = TABS.find((it) => it === searchParams.get("tab")) ?? "snapshots";
  const [tab, setTab] = useState<Tab>(initialTab);

  const selectTab = (id: Tab) => {
    setTab(id);
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set("tab", id);
      return next;
    }, { replace: true });
  };

  const tabs = [
    { value: "snapshots", label: t("networth.snapshots") },
    { value: "evolution", label: t("networth.evolution") },
    { value: "allocation", label: t("networth.allocation") },
    { value: "movements", label: t("networth.movements") },
    { value: "liabilities", label: t("networth.liabilities") },
    { value: "loans", label: t("networth.loans") },
  ];

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">{t("networth.title")}</h1>
      <TabBar items={tabs} value={tab} onChange={(v) => selectTab(v as Tab)} ariaLabel={t("networth.title")} />
      {tab === "snapshots" && <SnapshotsTab />}
      {tab === "evolution" && <EvolutionTab />}
      {tab === "allocation" && <AllocationTab />}
      {tab === "movements" && <MovementsTab />}
      {tab === "liabilities" && <LiabilitiesTab />}
      {tab === "loans" && <LoansTab />}
    </div>
  );
}
