import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { TabBar } from "@/components/ui/TabBar";
import { SnapshotsTab } from "./SnapshotsTab";
import { EvolutionTab } from "./EvolutionTab";
import { MovementsTab } from "./MovementsTab";
import { AssetsTab } from "./AssetsTab";
import { LiabilitiesTab } from "./LiabilitiesTab";
import { LoansTab } from "../loans/LoansTab";
import { HoldingsTab } from "../portfolio/HoldingsTab";
import { PortfolioAllocationTab } from "../portfolio/PortfolioAllocationTab";
import { PortfolioEvolutionTab } from "../portfolio/PortfolioEvolutionTab";

type Tab = "holdings" | "allocation" | "evolution" | "snapshots" | "movements" | "assets" | "liabilities" | "loans";

const TABS: Tab[] = ["holdings", "allocation", "evolution", "snapshots", "movements", "assets", "liabilities", "loans"];

export function NetWorthPage() {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const initialTab = TABS.find((it) => it === searchParams.get("tab")) ?? "holdings";
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
    { value: "holdings", label: t("portfolio.title") },
    { value: "allocation", label: t("portfolio.allocation") },
    { value: "evolution", label: t("portfolio.evolution") },
    { value: "snapshots", label: t("networth.snapshots") },
    { value: "movements", label: t("networth.movements") },
    { value: "assets", label: t("networth.assets") },
    { value: "liabilities", label: t("networth.liabilities") },
    { value: "loans", label: t("networth.loans") },
  ];

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">{t("nav.wealth")}</h1>
      <TabBar items={tabs} value={tab} onChange={(v) => selectTab(v as Tab)} ariaLabel={t("nav.wealth")} />
      {tab === "holdings" && <HoldingsTab />}
      {tab === "allocation" && <PortfolioAllocationTab />}
      {tab === "evolution" && (
        <div className="space-y-6">
          <section className="space-y-2">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
              {t("wealth.evolution_from_portfolio")}
            </h2>
            <PortfolioEvolutionTab />
          </section>
          <section className="space-y-2">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
              {t("wealth.evolution_from_snapshots")}
            </h2>
            <EvolutionTab />
          </section>
        </div>
      )}
      {tab === "snapshots" && <SnapshotsTab />}
      {tab === "movements" && <MovementsTab />}
      {tab === "assets" && <AssetsTab />}
      {tab === "liabilities" && <LiabilitiesTab />}
      {tab === "loans" && <LoansTab />}
    </div>
  );
}
