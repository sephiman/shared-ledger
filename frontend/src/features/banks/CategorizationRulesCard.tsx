import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  useCategorizationRules,
  useCreateRule,
  useDeleteRule,
  type Direction,
  type RuleField,
  type RuleOp,
} from "@/api/banks";
import { useCategories } from "@/api/catalog";
import { categoryLabel, categoryLabelByCode } from "@/lib/categoryLabel";
import { Button, Card, CardBody, CardHeader, Input, Label, Select } from "@/components/ui/primitives";

export function CategorizationRulesCard({ householdId, isOwner }: { householdId: string; isOwner: boolean }) {
  const { t } = useTranslation();
  const { data: rules = [] } = useCategorizationRules(householdId);
  const { data: categories = [] } = useCategories(householdId);
  const create = useCreateRule(householdId);
  const del = useDeleteRule(householdId);

  const [matchField, setMatchField] = useState<RuleField>("counterparty");
  const [matchOp, setMatchOp] = useState<RuleOp>("contains");
  const [matchValue, setMatchValue] = useState("");
  const [direction, setDirection] = useState<Direction>("expense");
  const [categoryCode, setCategoryCode] = useState("");

  const categoryOptions = categories.filter((c) => c.kind === direction);

  const submit = async () => {
    if (!matchValue || !categoryCode) return;
    await create.mutateAsync({ matchField, matchOp, matchValue, direction, categoryCode });
    setMatchValue("");
    setCategoryCode("");
  };

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("banks.rules_title")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("banks.rules_description")}</p>
      </CardHeader>
      <CardBody className="space-y-4">
        {isOwner && (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-6">
            <div>
              <Label>{t("banks.rule_field")}</Label>
              <Select value={matchField} onChange={(e) => setMatchField(e.target.value as RuleField)}>
                <option value="counterparty">{t("banks.field_counterparty")}</option>
                <option value="description">{t("banks.field_description")}</option>
                <option value="amount">{t("banks.field_amount")}</option>
              </Select>
            </div>
            <div>
              <Label>{t("banks.rule_op")}</Label>
              <Select value={matchOp} onChange={(e) => setMatchOp(e.target.value as RuleOp)}>
                <option value="contains">{t("banks.op_contains")}</option>
                <option value="equals">{t("banks.op_equals")}</option>
                <option value="range">{t("banks.op_range")}</option>
              </Select>
            </div>
            <div>
              <Label>{t("banks.rule_value")}</Label>
              <Input value={matchValue} onChange={(e) => setMatchValue(e.target.value)} placeholder={matchOp === "range" ? "10..50" : ""} />
            </div>
            <div>
              <Label>{t("common.direction")}</Label>
              <Select value={direction} onChange={(e) => { setDirection(e.target.value as Direction); setCategoryCode(""); }}>
                <option value="expense">{t("common.expense")}</option>
                <option value="income">{t("common.income")}</option>
              </Select>
            </div>
            <div>
              <Label>{t("common.category")}</Label>
              <Select value={categoryCode} onChange={(e) => setCategoryCode(e.target.value)}>
                <option value="">{t("banks.pick_category")}</option>
                {categoryOptions
                  .slice()
                  .sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t)))
                  .map((c) => (
                    <option key={c.code} value={c.code}>{categoryLabel(c, t)}</option>
                  ))}
              </Select>
            </div>
            <div className="flex items-end">
              <Button disabled={create.isPending || !matchValue || !categoryCode} onClick={submit}>
                {t("banks.add_rule")}
              </Button>
            </div>
          </div>
        )}

        {rules.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("banks.no_rules")}</p>
        ) : (
          <ul className="space-y-2">
            {rules.map((r) => (
              <li key={r.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border p-3 dark:border-gray-700">
                <span className="text-sm">
                  <span className="font-medium">{t(`banks.field_${r.matchField}`)}</span>{" "}
                  {t(`banks.op_${r.matchOp}`)} <code className="rounded bg-gray-100 px-1 dark:bg-gray-700">{r.matchValue}</code>{" "}
                  → {categoryLabelByCode(r.categoryCode, categories, t)} ({t(`common.${r.direction}`)})
                  {r.source === "learned" && (
                    <span className="ml-2 rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600 dark:bg-gray-800 dark:text-gray-300">
                      {t("banks.rule_learned")}
                    </span>
                  )}
                </span>
                {isOwner && (
                  <Button variant="ghost" className="px-2 text-red-600 dark:text-red-400" onClick={() => del.mutate(r.id)}>
                    <span aria-hidden>🗑️</span>
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </CardBody>
    </Card>
  );
}
