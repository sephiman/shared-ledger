import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router-dom";
import { TabBar } from "@/components/ui/TabBar";
import { MovementsTab } from "./MovementsTab";
import { CashTab } from "./CashTab";

type Flow = "movements" | "cash";
const FLOWS: Flow[] = ["movements", "cash"];

/**
 * Container for how money moves: capital reallocations (Movements) and cash adjustments (Cash).
 * Movements is the established sub-tab and the default; the active one lives in a `flow` search
 * param alongside the hub's `tab` param.
 */
export function FlowsTab() {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const initial = FLOWS.find((it) => it === searchParams.get("flow")) ?? "movements";
  const [flow, setFlow] = useState<Flow>(initial);

  const select = (id: Flow) => {
    setFlow(id);
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.set("flow", id);
        return next;
      },
      { replace: true },
    );
  };

  const tabs = [
    { value: "movements", label: t("networth.movements") },
    { value: "cash", label: t("networth.cash") },
  ];

  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-500 dark:text-gray-400">{t("networth.flows_description")}</p>
      <TabBar items={tabs} value={flow} onChange={(v) => select(v as Flow)} ariaLabel={t("networth.flows")} />
      {flow === "movements" && <MovementsTab />}
      {flow === "cash" && <CashTab />}
    </div>
  );
}
