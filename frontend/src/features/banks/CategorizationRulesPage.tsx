import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  useCategorizationRules,
  useCreateRule,
  useDeleteRule,
  useDeleteRules,
  useUpdateRule,
  type CategorizationRule,
  type Direction,
  type RuleField,
  type RuleOp,
  type RuleSource,
} from "@/api/banks";
import { useCategories, type Category } from "@/api/catalog";
import { categoryLabel, categoryLabelByCode } from "@/lib/categoryLabel";
import { formatDate } from "@/lib/dates";
import { Badge, Button, Card, CardBody, CardHeader, Checkbox, Input, Label, Select } from "@/components/ui/primitives";
import {
  RULE_FILTER_DEFAULTS,
  filterRules,
  hasActiveRuleFilters,
  sortRules,
  type RuleFilterState,
  type RuleSortKey,
} from "./ruleFilters";

const PAGE_SIZE = 25;

export function CategorizationRulesPage() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const householdId = household.householdId;
  const isOwner = household.role === "owner";

  const { data: rules = [], isLoading } = useCategorizationRules(householdId);
  const { data: categories = [] } = useCategories(householdId);
  const del = useDeleteRule(householdId);
  const delBatch = useDeleteRules(householdId);

  const [filters, setFiltersState] = useState<RuleFilterState>(RULE_FILTER_DEFAULTS);
  const [sort, setSort] = useState<RuleSortKey>("newest");
  const [cpage, setCpage] = useState(0);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [confirmBulkDelete, setConfirmBulkDelete] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  // Changing what's visible resets paging and selection, so bulk actions only ever cover visible rows.
  const setFilters = (patch: Partial<RuleFilterState>) => {
    setFiltersState((prev) => ({ ...prev, ...patch }));
    setCpage(0);
    setSelected(new Set());
    setConfirmBulkDelete(false);
  };

  const filtered = useMemo(
    () => sortRules(filterRules(rules, filters), sort, (code) => categoryLabelByCode(code, categories, t)),
    [rules, filters, sort, categories, t],
  );
  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const pageItems = filtered.slice(cpage * PAGE_SIZE, cpage * PAGE_SIZE + PAGE_SIZE);

  const allSelected = filtered.length > 0 && filtered.every((r) => selected.has(r.id));
  const toggleSelected = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const toggleSelectAll = () => setSelected(allSelected ? new Set() : new Set(filtered.map((r) => r.id)));

  const bulkDelete = async () => {
    await delBatch.mutateAsync([...selected]);
    setSelected(new Set());
    setConfirmBulkDelete(false);
    setCpage(0);
  };

  const sortedCategories = categories
    .slice()
    .sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t)));

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">{t("banks.rules_title")}</h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("banks.rules_page_description")}</p>
        </div>
        <Link to="/settings" className="text-sm text-primary">{t("common.back")}</Link>
      </div>

      {isOwner && <AddRuleCard householdId={householdId} categories={categories} />}

      <Card>
        <CardBody className="space-y-4">
          <div className="grid grid-cols-1 gap-3 md:grid-cols-6">
            <div className="md:col-span-2">
              <Label>{t("common.search")}</Label>
              <Input
                value={filters.search}
                onChange={(e) => setFilters({ search: e.target.value })}
                placeholder={t("banks.rules_search_placeholder")}
              />
            </div>
            <div>
              <Label>{t("banks.rule_field")}</Label>
              <Select value={filters.field} onChange={(e) => setFilters({ field: e.target.value as RuleField | "all" })}>
                <option value="all">{t("common.all")}</option>
                <option value="counterparty">{t("banks.field_counterparty")}</option>
                <option value="description">{t("banks.field_description")}</option>
                <option value="amount">{t("banks.field_amount")}</option>
              </Select>
            </div>
            <div>
              <Label>{t("common.direction")}</Label>
              <Select value={filters.direction} onChange={(e) => setFilters({ direction: e.target.value as Direction | "all" })}>
                <option value="all">{t("common.all")}</option>
                <option value="expense">{t("common.expense")}</option>
                <option value="income">{t("common.income")}</option>
              </Select>
            </div>
            <div>
              <Label>{t("common.category")}</Label>
              <Select value={filters.categoryCode} onChange={(e) => setFilters({ categoryCode: e.target.value })}>
                <option value="">{t("common.all")}</option>
                {sortedCategories.map((c) => (
                  <option key={c.code} value={c.code}>{categoryLabel(c, t)}</option>
                ))}
              </Select>
            </div>
            <div>
              <Label>{t("banks.rules_origin")}</Label>
              <Select value={filters.source} onChange={(e) => setFilters({ source: e.target.value as RuleSource | "all" })}>
                <option value="all">{t("common.all")}</option>
                <option value="manual">{t("banks.origin_manual")}</option>
                <option value="learned">{t("banks.origin_learned")}</option>
              </Select>
            </div>
            <div>
              <Label>{t("banks.sort_by")}</Label>
              <Select value={sort} onChange={(e) => { setSort(e.target.value as RuleSortKey); setCpage(0); }}>
                <option value="newest">{t("banks.sort_newest")}</option>
                <option value="oldest">{t("banks.sort_oldest")}</option>
                <option value="value">{t("banks.sort_value")}</option>
                <option value="category">{t("banks.sort_category")}</option>
              </Select>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-3 text-sm">
            {isOwner && filtered.length > 0 && (
              <label className="inline-flex cursor-pointer items-center gap-2">
                <Checkbox
                  checked={allSelected}
                  indeterminate={selected.size > 0 && !allSelected}
                  onChange={toggleSelectAll}
                />
                {t("banks.select_all_n", { count: filtered.length })}
              </label>
            )}
            {selected.size > 0 && (
              <>
                <span className="text-gray-500 dark:text-gray-400">{t("banks.n_selected", { count: selected.size })}</span>
                <Button variant="danger" className="px-3 py-1.5" onClick={() => setConfirmBulkDelete(true)}>
                  {t("banks.delete_selected", { count: selected.size })}
                </Button>
                <Button variant="ghost" className="px-3 py-1.5" onClick={() => setSelected(new Set())}>
                  {t("banks.clear_selection")}
                </Button>
              </>
            )}
            <span className="ml-auto text-gray-500 dark:text-gray-400">
              {t("banks.rules_count", { count: filtered.length })}
            </span>
            {hasActiveRuleFilters(filters) && (
              <Button variant="ghost" className="px-3 py-1.5" onClick={() => setFilters(RULE_FILTER_DEFAULTS)}>
                {t("banks.clear_filters")}
              </Button>
            )}
          </div>

          {confirmBulkDelete && selected.size > 0 && (
            <div className="flex flex-wrap items-center gap-2 rounded-md border border-red-300 bg-red-50 p-3 dark:border-red-800 dark:bg-red-950/30">
              <p className="flex-1 text-sm text-red-800 dark:text-red-300">
                {t("banks.delete_rules_confirm", { count: selected.size })}
              </p>
              <Button variant="danger" disabled={delBatch.isPending} onClick={bulkDelete}>{t("common.delete")}</Button>
              <Button variant="secondary" onClick={() => setConfirmBulkDelete(false)}>{t("common.cancel")}</Button>
            </div>
          )}

          {isLoading ? (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
          ) : rules.length === 0 ? (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("banks.no_rules")}</p>
          ) : filtered.length === 0 ? (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("banks.no_matching_rules")}</p>
          ) : (
            <>
              <ul className="space-y-2">
                {pageItems.map((r) => (
                  <li key={r.id} className="rounded-md border border-border p-3 dark:border-gray-700">
                    {editingId === r.id ? (
                      <RuleEditor
                        householdId={householdId}
                        rule={r}
                        categories={categories}
                        onClose={() => setEditingId(null)}
                      />
                    ) : (
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex min-w-0 items-start gap-3">
                          {isOwner && (
                            <Checkbox
                              className="mt-1"
                              checked={selected.has(r.id)}
                              onChange={() => toggleSelected(r.id)}
                              aria-label={t("banks.select_rule")}
                            />
                          )}
                          <div className="min-w-0 break-words text-sm">
                            <span className="font-medium">{t(`banks.field_${r.matchField}`)}</span>{" "}
                            {t(`banks.op_${r.matchOp}`)}{" "}
                            <code className="rounded bg-gray-100 px-1 dark:bg-gray-700">{r.matchValue}</code>{" "}
                            → {categoryLabelByCode(r.categoryCode, categories, t)} ({t(`common.${r.direction}`)})
                            {r.source === "learned" && <Badge className="ml-2">{t("banks.rule_learned")}</Badge>}
                            <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                              {formatDate(r.createdAt, i18n.language)}
                            </p>
                          </div>
                        </div>
                        {isOwner && (
                          <div className="inline-flex gap-1">
                            <Button
                              variant="ghost"
                              className="px-2"
                              aria-label={t("common.edit")}
                              title={t("common.edit")}
                              onClick={() => setEditingId(r.id)}
                            >
                              <span aria-hidden>✏️</span>
                            </Button>
                            <Button
                              variant="ghost"
                              className="px-2 text-red-600 dark:text-red-400"
                              aria-label={t("common.delete")}
                              title={t("common.delete")}
                              disabled={del.isPending}
                              onClick={() => {
                                if (selected.has(r.id)) toggleSelected(r.id);
                                del.mutate(r.id);
                              }}
                            >
                              <span aria-hidden>🗑️</span>
                            </Button>
                          </div>
                        )}
                      </div>
                    )}
                  </li>
                ))}
              </ul>
              {pageCount > 1 && (
                <div className="flex items-center justify-between text-sm">
                  <span className="text-gray-500 dark:text-gray-400">{t("common.page_of", { page: cpage + 1, total: pageCount })}</span>
                  <div className="flex gap-2">
                    <Button variant="secondary" disabled={cpage === 0} onClick={() => setCpage((p) => Math.max(0, p - 1))}>
                      {t("common.prev")}
                    </Button>
                    <Button variant="secondary" disabled={cpage + 1 >= pageCount} onClick={() => setCpage((p) => p + 1)}>
                      {t("common.next")}
                    </Button>
                  </div>
                </div>
              )}
            </>
          )}
        </CardBody>
      </Card>
    </div>
  );
}

function AddRuleCard({ householdId, categories }: { householdId: string; categories: Category[] }) {
  const { t } = useTranslation();
  const create = useCreateRule(householdId);

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
        <p className="font-medium">{t("banks.add_rule")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("banks.rules_description")}</p>
      </CardHeader>
      <CardBody>
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
      </CardBody>
    </Card>
  );
}

function RuleEditor({
  householdId,
  rule,
  categories,
  onClose,
}: {
  householdId: string;
  rule: CategorizationRule;
  categories: Category[];
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const update = useUpdateRule(householdId);

  const [matchField, setMatchField] = useState<RuleField>(rule.matchField);
  const [matchOp, setMatchOp] = useState<RuleOp>(rule.matchOp);
  const [matchValue, setMatchValue] = useState(rule.matchValue);
  const [direction, setDirection] = useState<Direction>(rule.direction);
  const [categoryCode, setCategoryCode] = useState(rule.categoryCode);

  const categoryOptions = categories.filter((c) => c.kind === direction);

  const save = async () => {
    if (!matchValue || !categoryCode) return;
    await update.mutateAsync({ id: rule.id, input: { matchField, matchOp, matchValue, direction, categoryCode } });
    onClose();
  };

  return (
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
      <div className="flex items-end gap-2">
        <Button disabled={update.isPending || !matchValue || !categoryCode} onClick={save}>
          {t("common.save")}
        </Button>
        <Button variant="secondary" onClick={onClose}>{t("common.cancel")}</Button>
      </div>
    </div>
  );
}
