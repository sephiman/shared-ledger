import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useSnapshots } from "@/api/networth";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { Legend, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import { formatDate } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { ChartTooltip } from "@/components/charts/ChartTooltip";

const PALETTE = ["#0ea5e9", "#22c55e", "#a855f7", "#f97316", "#ef4444", "#14b8a6"];

export function AllocationTab({ title }: { title?: string } = {}) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: snapshots = [] } = useSnapshots(household.householdId);
  const latest = snapshots[snapshots.length - 1];

  const data = useMemo(() => {
    if (!latest) return [];
    return latest.assets.map((a, i) => ({
      name: t(`asset.${a.assetClassCode}`),
      value: Number(a.value),
      fill: PALETTE[i % PALETTE.length],
    }));
  }, [latest, t]);

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{title ?? t("networth.allocation")} {latest ? `· ${formatDate(latest.snapshotDate, i18n.language)}` : ""}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("networth.allocation_description")}</p>
      </CardHeader>
      <CardBody className="h-96">
        {data.length === 0 ? (
          <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie data={data} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={120} label />
              <Tooltip
                content={(props) => (
                  <ChartTooltip
                    {...props}
                    formatValue={(v) => formatMoney(Number(v), household.currency, i18n.language)}
                  />
                )}
              />
              <Legend />
            </PieChart>
          </ResponsiveContainer>
        )}
      </CardBody>
    </Card>
  );
}
