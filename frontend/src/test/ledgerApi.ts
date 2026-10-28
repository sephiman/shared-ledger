import { vi } from "vitest";
import type { AxiosRequestConfig, AxiosResponse } from "axios";
import { apiClient } from "@/api/client";
import type { ExplorerResponse, HeatmapResponse } from "@/api/analytics";
import type { Category } from "@/api/catalog";
import type { Transaction, TransactionPage, TransactionTotals } from "@/api/transactions";

/** A stubbed HTTP layer for the Explorer and the transactions list, so the real hooks decide what is requested. */

export const HOUSEHOLD = "h1";

export const categories: Category[] = [
  { code: "outings.restaurants", kind: "expense", group: "outings", sortOrder: 0, essential: false, name: "category.outings.restaurants", custom: false },
  { code: "outings.travel", kind: "expense", group: "outings", sortOrder: 1, essential: false, name: "category.outings.travel", custom: false },
  { code: "groceries.groceries", kind: "expense", group: "groceries", sortOrder: 0, essential: true, name: "category.groceries.groceries", custom: false },
  { code: "income.salary", kind: "income", group: null, sortOrder: 0, essential: false, name: "category.income.salary", custom: false },
];

export interface Requested {
  url: string;
  params: Record<string, unknown> | undefined;
}

export const requested: Requested[] = [];
export const posted: { url: string; body: unknown }[] = [];

const ok = <T,>(data: T) => Promise.resolve({ data } as AxiosResponse<T>);

const explorer = (params: Record<string, unknown> | undefined): ExplorerResponse => ({
  scopeType: (params?.scopeType as "group" | "category" | undefined) ?? "group",
  scopeCode: (params?.scopeCode as string | undefined) ?? "groceries",
  months: [{ year: 2026, month: 9, amount: "120.00" }],
  priorMonths: null,
  priorYearsAvailable: 2,
  averagePerMonth: "120.00",
  medianPerMonth: "120.00",
  highestMonth: null,
  lowestNonZeroMonth: null,
  topDescriptions: null,
});

const heatmap: HeatmapResponse = { direction: "expense", months: [{ year: 2026, month: 9 }], categories: [] };
const emptyPage: TransactionPage = { items: [], page: 0, size: 50, total: 0 };
const zeroTotals: TransactionTotals = { count: 0, income: "0.00", expenses: "0.00", net: "0.00" };

/** A listed transaction, for tests that need the list non-empty. */
export const listedTransaction = (patch: Partial<Transaction> = {}): Transaction => ({
  id: `t-${Math.random()}`, occurrenceDate: "2026-03-10", direction: "expense", categoryCode: "outings.restaurants",
  amount: "20.00", description: null, recurringTemplateId: null, createdByUserId: "u1", updatedByUserId: "u1",
  createdAt: "2026-03-10T10:00:00Z", updatedAt: "2026-03-10T10:00:00Z", isRefund: false, refundOfTransactionId: null,
  refundOf: null, refundedTotal: null, refundCount: null, ...patch,
});

function route(url: string, params: Record<string, unknown> | undefined): unknown {
  const base = `/households/${HOUSEHOLD}`;
  if (url === "/features") return { ai: true };
  if (url === `${base}/categories`) return categories;
  if (url === `${base}/transactions`) return listPage;
  if (url === `${base}/transactions/totals`) return totalsFor(params);
  if (url === `${base}/analytics/explorer`) return explorer(params);
  if (url === `${base}/analytics/heatmap`) return heatmap;
  if (url === `${base}/banks/config`) return { credentialsConfigured: false, connectionCount: 0, nextSyncTimes: [] };
  if (url === `${base}/questions/example-category`) return { categoryCode: exampleCode };
  if (url === `${base}/ai-suggestions/settings`) return { enabled: aiOn, model: "test-model", providerHost: "ai.test" };
  throw new Error(`unexpected GET ${url}`);
}

let aiOn = true;
let exampleCode: string | null = "outings.restaurants";
let listPage: TransactionPage = emptyPage;
let totalsFor: (params: Record<string, unknown> | undefined) => TransactionTotals = () => zeroTotals;

/** [onPost] answers POSTs; by default none is expected. [ai] is the household's AI setting, [example] the placeholder
 *  category the server picks, [page] the list's page and [totals] its totals line for the requested filters. */
export function stubLedgerApi(
  onPost: (url: string, body: unknown) => unknown = (url) => {
    throw new Error(`unexpected POST ${url}`);
  },
  {
    ai = true,
    example = "outings.restaurants",
    page = emptyPage,
    totals = () => zeroTotals,
  }: {
    ai?: boolean;
    example?: string | null;
    page?: TransactionPage;
    totals?: (params: Record<string, unknown> | undefined) => TransactionTotals;
  } = {},
) {
  aiOn = ai;
  exampleCode = example;
  listPage = page;
  totalsFor = totals;
  requested.length = 0;
  posted.length = 0;
  vi.spyOn(apiClient, "get").mockImplementation((url: string, config?: AxiosRequestConfig) => {
    const params = config?.params as Record<string, unknown> | undefined;
    requested.push({ url, params });
    return ok(route(url, params));
  });
  vi.spyOn(apiClient, "post").mockImplementation((url: string, body?: unknown) => {
    posted.push({ url, body });
    return Promise.resolve(onPost(url, body)).then((data) => ({ data }) as AxiosResponse);
  });
}

/** The params of the last GET to [path] (relative to the household), or undefined if none was made. */
export function lastRequest(path: string): Record<string, unknown> | undefined {
  const url = `/households/${HOUSEHOLD}${path}`;
  return [...requested].reverse().find((r) => r.url === url)?.params;
}
