import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { AxiosResponse } from "axios";
import { apiClient } from "@/api/client";
import type { AiSuggestionSettings } from "@/api/aiSuggestions";
import type { CategorizationRule } from "@/api/banks";
import type { Category } from "@/api/catalog";
import type {
  ProposalCheck,
  RuleGeneralisationProposal,
  RuleGeneralisationRun,
  RuleGeneralisationStatus,
} from "@/api/ruleGeneralisations";
import i18n from "@/i18n";
import { CategorizationRulesPage } from "./CategorizationRulesPage";

/** The rules page against a stubbed HTTP layer, so the real hooks decide what is requested and rendered. */

vi.mock("@/auth/AuthContext", () => ({ useActiveHousehold: () => ({ householdId: "h1" }) }));

const PATH = "/households/h1/banks/rules/generalisations";

const categories: Category[] = [
  { code: "groceries.groceries", kind: "expense", group: "groceries", sortOrder: 0, essential: true, name: "category.groceries.groceries", custom: false },
  { code: "outings.travel", kind: "expense", group: "outings", sortOrder: 0, essential: false, name: "category.outings.travel", custom: false },
];

const rule = (id: string, matchValue: string): CategorizationRule => ({
  id, matchField: "counterparty", matchOp: "equals", matchValue, categoryCode: "groceries.groceries",
  direction: "expense", priority: 200, source: "learned", createdAt: "2026-10-01T10:00:00Z",
});

const run = (patch: Partial<RuleGeneralisationRun>): RuleGeneralisationRun => ({
  id: "run1", status: "running", rulesTotal: 17, rulesSent: 17, proposalsReturned: 0, proposalsDiscarded: 0,
  proposalsShown: 0, proposalsAccepted: 0, startedAt: "2026-10-27T10:00:00Z", finishedAt: null, ...patch,
});

const okCheck: ProposalCheck = {
  status: "ok", problem: null, collisionCount: 0, collisionExamples: [], changeCount: 0, changeExamples: [],
};

const proposals: RuleGeneralisationProposal[] = [
  {
    id: "p1", pattern: "Albert Heijn", field: "counterparty", direction: "expense", categoryCode: "groceries.groceries", confidence: 0.95,
    absorbed: [{ id: "a1", matchValue: "Albert Heijn 8591" }, { id: "a2", matchValue: "Albert Heijn 1077 Amsterdam" }],
    check: okCheck,
  },
  {
    id: "p2", pattern: "Lidl", field: "counterparty", direction: "expense", categoryCode: "groceries.groceries", confidence: 0.93,
    absorbed: [{ id: "l1", matchValue: "Lidl 737 Almere" }, { id: "l2", matchValue: "LIDL 1203 Almere Haven" }],
    check: { ...okCheck, status: "conflict", collisionCount: 1, collisionExamples: ["Lidl Reisen"] },
  },
];

const status = (patch: Partial<RuleGeneralisationStatus>): RuleGeneralisationStatus => ({
  eligibleRules: 17, minRules: 10, maxRules: 300, run: null, proposals: null, proposalsExpired: false, ...patch,
});

interface Server {
  features: boolean;
  settings: AiSuggestionSettings;
  statuses: RuleGeneralisationStatus[];
  validate: (body: { pattern: string }) => ProposalCheck;
}

let server: Server;
const requested: string[] = [];
const posted: { url: string; body: unknown }[] = [];

const ok = <T,>(data: T) => Promise.resolve({ data } as AxiosResponse<T>);

function route(url: string): unknown {
  if (url === "/features") return { aiCategorySuggestions: server.features };
  if (url === "/households/h1/ai-suggestions/settings") return server.settings;
  if (url === "/households/h1/banks/rules") return [rule("a1", "Albert Heijn 8591"), rule("a2", "Albert Heijn 1077 Amsterdam")];
  if (url === "/households/h1/categories") return categories;
  if (url === PATH) return server.statuses.length > 1 ? server.statuses.shift() : server.statuses[0];
  throw new Error(`unexpected GET ${url}`);
}

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

beforeEach(() => {
  requested.length = 0;
  posted.length = 0;
  server = {
    features: true,
    settings: { enabled: true, model: "deepseek-v4.1-flash", providerHost: "opencode.ai" },
    statuses: [status({})],
    validate: () => okCheck,
  };
  vi.spyOn(apiClient, "get").mockImplementation((url: string) => {
    requested.push(url);
    return ok(route(url));
  });
  vi.spyOn(apiClient, "post").mockImplementation((url: string, body?: unknown) => {
    posted.push({ url, body });
    if (url === PATH) {
      server.statuses = [status({ run: run({}) }), status({ run: run({ status: "completed", proposalsReturned: 3, proposalsShown: 2, proposalsDiscarded: 1 }), proposals })];
      return ok(run({}));
    }
    if (url === `${PATH}/validate`) return ok(server.validate(body as { pattern: string }));
    if (url === `${PATH}/accept`) return ok({ rule: rule("new", "Albert Heijn"), deleted: 2 });
    throw new Error(`unexpected POST ${url}`);
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function renderPage(state?: unknown) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[{ pathname: "/settings/rules", state }]}>
        <CategorizationRulesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const button = () => screen.findByRole("button", { name: "Suggest generalised rules" });

async function runSuggestions() {
  const user = userEvent.setup();
  renderPage();
  await user.click(await button());
  await screen.findByText("Generalised rules — shown: 2, discarded by validation: 1, with collisions: 1", {}, { timeout: 3000 });
  return user;
}

describe("Suggest generalised rules: visibility", () => {
  it("does not exist, nor is anything AI requested, when the instance has no provider", async () => {
    server.features = false;
    renderPage();

    await screen.findByText("Albert Heijn 8591");
    expect(screen.queryByRole("button", { name: "Suggest generalised rules" })).toBeNull();
    expect(requested).not.toContain("/households/h1/ai-suggestions/settings");
    expect(requested).not.toContain(PATH);
  });

  it("does not appear on the rules page while the household setting is off", async () => {
    server.settings = { ...server.settings, enabled: false };
    renderPage();

    await waitFor(() => expect(requested).toContain("/households/h1/ai-suggestions/settings"));
    await screen.findByText("Albert Heijn 8591");
    expect(screen.queryByRole("button", { name: "Suggest generalised rules" })).toBeNull();
    expect(requested).not.toContain(PATH);
  });

  it("appears with the setting on, saying what is shared and that nothing changes until a proposal is accepted", async () => {
    renderPage();

    await waitFor(async () => expect(await button()).toBeEnabled());
    const tip = screen.getByRole("tooltip", { hidden: true });
    expect(tip.textContent).toContain("Sends your rule patterns and their categories to deepseek-v4.1-flash (opencode.ai)");
    expect(tip.textContent).toContain("Nothing changes until you accept a proposal.");
  });

  it("is disabled, saying why, below the minimum number of counterparty or description rules", async () => {
    server.statuses = [status({ eligibleRules: 1, minRules: 2 })];
    renderPage();

    await waitFor(async () => expect(await button()).toBeDisabled());
    expect(screen.getByText("Needs at least 2 counterparty or description rules (this household has 1).")).toBeInTheDocument();
  });

  it("is disabled while a run is in progress, also one another member started", async () => {
    server.statuses = [status({ run: run({}) })];
    renderPage();

    await waitFor(async () => expect(await button()).toBeDisabled());
  });
});

describe("Suggest generalised rules: proposals", () => {
  it("shows one card per proposal with its rules and the server's verdict; a collision blocks accept and names examples", async () => {
    await runSuggestions();

    const ah = screen.getByRole("article", { name: "Proposal: Albert Heijn" });
    expect(within(ah).getByText("Albert Heijn 1077 Amsterdam")).toBeInTheDocument();
    expect(within(ah).getByText(/Covers every rule it replaces/)).toBeInTheDocument();
    expect(within(ah).getByRole("button", { name: "Accept" })).toBeEnabled();

    const lidl = screen.getByRole("article", { name: "Proposal: Lidl" });
    expect(within(lidl).getByText(/Also matches 1 counterparty this household files under another category/)).toBeInTheDocument();
    expect(within(lidl).getByText("Lidl Reisen")).toBeInTheDocument();
    expect(within(lidl).getByRole("button", { name: "Accept" })).toBeDisabled();
  });

  it("re-checks an edited pattern through the server and only then unlocks accept", async () => {
    const user = await runSuggestions();
    server.validate = ({ pattern }) =>
      pattern === "Lidl 7" ? { ...okCheck, status: "invalid", problem: "not_covering" } : okCheck;
    const lidl = screen.getByRole("article", { name: "Proposal: Lidl" });
    const input = within(lidl).getByLabelText("Pattern");

    await user.clear(input);
    await user.type(input, "Lidl 7");

    expect(within(lidl).getByText("Checking the pattern…")).toBeInTheDocument();
    expect(within(lidl).getByRole("button", { name: "Accept" })).toBeDisabled();
    expect(await within(lidl).findByText("The pattern does not appear in every rule it replaces.")).toBeInTheDocument();
    expect(posted.filter((p) => p.url === `${PATH}/validate`).at(-1)?.body).toMatchObject({
      pattern: "Lidl 7", field: "counterparty", absorbedRuleIds: ["l1", "l2"], categoryCode: "groceries.groceries", direction: "expense",
    });
    expect(within(lidl).getByRole("button", { name: "Accept" })).toBeDisabled();
  });

  it("accepts with the final pattern and the absorbed ids, then drops the card; dismiss drops the other", async () => {
    const user = await runSuggestions();

    await user.click(within(screen.getByRole("article", { name: "Proposal: Albert Heijn" })).getByRole("button", { name: "Accept" }));

    await waitFor(() => expect(screen.queryByRole("article", { name: "Proposal: Albert Heijn" })).toBeNull());
    expect(posted.find((p) => p.url === `${PATH}/accept`)?.body).toEqual({
      pattern: "Albert Heijn", field: "counterparty", direction: "expense", categoryCode: "groceries.groceries", absorbedRuleIds: ["a1", "a2"], runId: "run1",
    });

    await user.click(within(screen.getByRole("article", { name: "Proposal: Lidl" })).getByRole("button", { name: "Dismiss" }));
    expect(screen.queryByRole("article", { name: "Proposal: Lidl" })).toBeNull();
  });

  it("shows a description proposal and the known movements it would change, with accept blocked", async () => {
    const vodafone: RuleGeneralisationProposal = {
      id: "p3", pattern: "Vodafone", field: "description", direction: "expense", categoryCode: "groceries.groceries", confidence: 0.9,
      absorbed: [{ id: "d1", matchValue: "Vodafone Servicios" }, { id: "d2", matchValue: "Vodafone Ono" }],
      check: {
        ...okCheck, status: "conflict", changeCount: 1,
        changeExamples: [{ counterparty: "Lidl 737 Almere", description: "Vodafone recibo", fromCategory: "groceries.groceries", toCategory: "outings.travel" }],
      },
    };
    vi.mocked(apiClient.post).mockImplementation(() => {
      server.statuses = [status({ run: run({ status: "completed", proposalsShown: 1 }), proposals: [vodafone] })];
      return ok(run({}));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await button());

    const card = await screen.findByRole("article", { name: "Proposal: Vodafone" });
    expect(within(card).getByText(/^Description contains/)).toBeInTheDocument();
    expect(within(card).getByText("Accepting would change the category of 1 movement you already have:")).toBeInTheDocument();
    expect(within(card).getByText("Lidl 737 Almere · Vodafone recibo")).toBeInTheDocument();
    expect(within(card).getByRole("button", { name: "Accept" })).toBeDisabled();
  });

  it("says when only the most recent rules were sent", async () => {
    vi.mocked(apiClient.post).mockImplementation((url: string) => {
      server.statuses = [status({ run: run({ status: "completed", rulesTotal: 340, rulesSent: 300 }), proposals: [] })];
      return ok(run({ id: url === PATH ? "run1" : "x" }));
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await button());

    expect(await screen.findByText("Only the 300 most recent of 340 rules were sent.")).toBeInTheDocument();
  });
});

describe("rules page back link", () => {
  it("returns to settings when opened from there", async () => {
    renderPage();

    expect(await screen.findByRole("link", { name: "Back" })).toHaveAttribute("href", "/settings");
  });

  it("returns to the pending inbox when opened from it", async () => {
    renderPage({ backTo: "/transactions?tab=pending" });

    expect(await screen.findByRole("link", { name: "Back" })).toHaveAttribute("href", "/transactions?tab=pending");
  });

  it("ignores a back target that leaves the app", async () => {
    renderPage({ backTo: "//evil.example" });

    expect(await screen.findByRole("link", { name: "Back" })).toHaveAttribute("href", "/settings");
  });
});
