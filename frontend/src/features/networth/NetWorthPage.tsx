import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/cn";
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

  const tabs: { id: Tab; label: string }[] = [
    { id: "snapshots", label: t("networth.snapshots") },
    { id: "evolution", label: t("networth.evolution") },
    { id: "allocation", label: t("networth.allocation") },
    { id: "movements", label: t("networth.movements") },
    { id: "liabilities", label: t("networth.liabilities") },
    { id: "loans", label: t("networth.loans") },
  ];

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">{t("networth.title")}</h1>
      <div className="flex gap-1 border-b border-border">
        {tabs.map((it) => (
          <button
            key={it.id}
            onClick={() => selectTab(it.id)}
            className={cn(
              "px-3 py-2 text-sm font-medium",
              tab === it.id ? "border-b-2 border-primary text-primary" : "text-gray-600 dark:text-gray-300",
            )}
          >
            {it.label}
          </button>
        ))}
      </div>
      {tab === "snapshots" && <SnapshotsTab />}
      {tab === "evolution" && <EvolutionTab />}
      {tab === "allocation" && <AllocationTab />}
      {tab === "movements" && <MovementsTab />}
      {tab === "liabilities" && <LiabilitiesTab />}
      {tab === "loans" && <LoansTab />}
    </div>
  );
}
