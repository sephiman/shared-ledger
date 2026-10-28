import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, configure, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import i18n from "@/i18n";
import type { QuestionAnswer } from "@/api/questions";
import { formatDayMonthYear, isoToday } from "@/lib/dates";
import { fieldByLabel } from "@/test/LocationProbe";
import { posted, requested, stubLedgerApi } from "@/test/ledgerApi";
import { renderViews } from "@/test/renderViews";
import { setAskResult } from "./askResultStore";

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", currency: "EUR", role: "owner" }),
}));

// Whole views render here; under a loaded parallel run they need more than the default second.
configure({ asyncUtilTimeout: 4000 });
vi.setConfig({ testTimeout: 20000 });

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

beforeEach(() => {
  sessionStorage.clear();
  setAskResult(null);
});

afterEach(async () => {
  cleanup();
  vi.restoreAllMocks();
  await i18n.changeLanguage("en");
});

const location = () => screen.getByTestId("location").textContent ?? "";
const box = () => screen.findByRole("textbox", { name: i18n.t("ask.label") });
const line = async () => (await screen.findByText(i18n.t("ask.understood_as"))).closest("[role=status]") as HTMLElement;
const words = async () => (await screen.findByText(i18n.t("ask.understood_as"))).parentElement?.textContent;
const clearButton = async () => within(await line()).getByRole("button", { name: "Clear" });

const restaurantsSummer: QuestionAnswer = {
  understood: true,
  target: "explorer",
  reason: null,
  expressibleIn: ["explorer", "transactions"],
  filters: {
    direction: "expense",
    scopes: [{ type: "category", code: "outings.restaurants" }],
    range: { preset: "custom", from: "2025-06-01", to: "2025-08-31" },
    isRefund: false,
    priorYear: false,
  },
  unsupported: [],
};

const groceriesAndOutingsThisYear: QuestionAnswer = {
  understood: true,
  target: "explorer",
  reason: null,
  expressibleIn: ["explorer", "transactions"],
  filters: {
    direction: "expense",
    scopes: [{ type: "group", code: "groceries" }, { type: "group", code: "outings" }],
    range: { preset: "ytd" },
    isRefund: false,
    priorYear: false,
  },
  unsupported: [],
};

const today = () => formatDayMonthYear(isoToday(), "en");
const firstOfYear = () => `${isoToday().slice(0, 4)}-01-01`;

const incomeSoFarFromExplorer: QuestionAnswer = {
  understood: true,
  target: "transactions",
  reason: "income",
  expressibleIn: ["transactions"],
  filters: { direction: "income", scopes: [], range: { preset: "all" }, isRefund: false, priorYear: false },
  unsupported: [],
};

async function ask(question: string, answer: QuestionAnswer | (() => unknown), url = "/analytics/explorer") {
  stubLedgerApi(typeof answer === "function" ? answer : () => answer);
  const user = userEvent.setup();
  renderViews(url);
  await user.type(await box(), `${question}{Enter}`);
  return user;
}

describe("Ask: presence and copy", () => {
  it("does not exist while the household has AI features off", async () => {
    stubLedgerApi(undefined, { ai: false });
    renderViews("/transactions");

    await screen.findByRole("option", { name: /Restaurants/ });
    expect(screen.queryByRole("textbox", { name: "Ask" })).not.toBeInTheDocument();
  });

  it("offers an example with the household's most used expense category, and says what is sent", async () => {
    stubLedgerApi();
    renderViews("/transactions");

    await waitFor(async () => expect(await box()).toHaveAttribute("placeholder", "How much on Restaurants & bars last summer?"));
    const tip = screen.getByRole("tooltip", { hidden: true });
    expect(tip.textContent).toContain("Only your question and the list of categories are sent to test-model (ai.test)");
    expect(tip.textContent).toContain("only a filter configuration");
  });

  it("falls back to a generic example when the household has no expense category to name", async () => {
    stubLedgerApi(undefined, { example: null });
    renderViews("/transactions");

    expect(await box()).toHaveAttribute("placeholder", "How much did we spend last summer?");
  });

  it("asks for the example once, kept with the catalogue, whichever view the box is in", async () => {
    const user = await ask("How much on restaurants last summer?", restaurantsSummer, "/transactions");
    await user.click(await clearButton());

    expect(requested.filter((r) => r.url === "/households/h1/questions/example-category")).toHaveLength(1);
  });

  it("speaks Spanish", async () => {
    await i18n.changeLanguage("es");
    await ask("¿Cuánto en restaurantes el verano pasado?", restaurantsSummer);

    expect(await words()).toBe("Entendido como: Explorador · Ocio → Restaurantes y bares · jun 2025 – ago 2025 · sin año anterior");
    expect(within(await line()).getByRole("link", { name: "Ver movimientos →" })).toBeInTheDocument();
    await userEvent.setup().click(within(await line()).getByRole("link", { name: "Ver movimientos →" }));
    expect(within(await line()).getByRole("link", { name: "Mejor en Explorador →" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Pregunta" })).toHaveAttribute("placeholder", "¿Cuánto en Restaurantes y bares el verano pasado?");
    expect(posted[0].body).toEqual({ question: "¿Cuánto en restaurantes el verano pasado?", language: "es", askedFrom: "explorer" });
  });
});

describe("Ask: where the answer goes", () => {
  it("shows what it understood, in words, on Explorer, saying where it was asked from", async () => {
    await ask("How much on restaurants last summer?", restaurantsSummer);

    await waitFor(() => expect(location()).toMatch(/^\/analytics\/explorer\?/));
    expect(await words()).toBe("Understood as: Explorer · Outings → Restaurants & bars · Jun 2025 – Aug 2025 · no prior year");
    expect(posted[0]).toEqual({
      url: "/households/h1/questions",
      body: { question: "How much on restaurants last summer?", language: "en", askedFrom: "explorer" },
    });
  });

  it("how much income so far, asked from Explorer, opens the list over all time and says why", async () => {
    await ask("How much income so far?", incomeSoFarFromExplorer);

    await waitFor(() => expect(location()).toBe(`/transactions?to=${isoToday()}&direction=income`));
    expect(await words()).toBe(`Understood as: Transactions · income · all time until ${today()}`);
    expect(within(await line()).getByText("income questions open the transactions list")).toBeInTheDocument();
    expect(within(await line()).queryByRole("link")).not.toBeInTheDocument();
  });

  it("asked from the list, the same question stays there without a reason", async () => {
    await ask("How much income so far?", { ...incomeSoFarFromExplorer, reason: null }, "/transactions");

    await waitFor(() => expect(location()).toBe(`/transactions?to=${isoToday()}&direction=income`));
    expect(screen.queryByText(/open the transactions list/)).not.toBeInTheDocument();
  });

  it("so far this year reads as year to date, not all time", async () => {
    await ask("Restaurants so far this year", {
      ...restaurantsSummer,
      filters: { ...restaurantsSummer.filters!, range: { preset: "ytd" } },
    });

    expect(await words()).toBe("Understood as: Explorer · Outings → Restaurants & bars · year to date · no prior year");
  });

  it("on the list, a preset reads as the dates actually applied, after whole-month snapping", async () => {
    await ask("Restaurants so far this year", {
      ...restaurantsSummer,
      target: "transactions",
      filters: { ...restaurantsSummer.filters!, range: { preset: "ytd" } },
    }, "/transactions");

    await waitFor(() => expect(location()).toContain(`from=${firstOfYear()}&to=${isoToday()}`));
    expect(await words()).toBe(
      `Understood as: Transactions · expenses · Outings → Restaurants & bars · ${formatDayMonthYear(firstOfYear(), "en")} – ${today()}`,
    );
  });

  it("a comparison with last year turns Explorer's overlay on", async () => {
    await ask("Restaurants last summer vs the year before", {
      ...restaurantsSummer,
      filters: { ...restaurantsSummer.filters!, priorYear: true },
    });

    await waitFor(() => expect(location()).toContain("prior=1"));
    expect(await words()).toBe("Understood as: Explorer · Outings → Restaurants & bars · Jun 2025 – Aug 2025 · with prior year");
    expect(screen.queryByText(/Can't filter by/)).not.toBeInTheDocument();
  });

  it("the overlay is silently left out on the list, which has none", async () => {
    await ask("Restaurants last summer vs the year before", {
      ...restaurantsSummer,
      target: "transactions",
      filters: { ...restaurantsSummer.filters!, priorYear: true },
    }, "/transactions");

    await waitFor(() => expect(location()).toBe("/transactions?from=2025-06-01&to=2025-08-31&direction=expense&categoryCode=outings.restaurants"));
    expect(await words()).not.toMatch(/prior year/);
    expect(screen.queryByText(/Can't filter by/)).not.toBeInTheDocument();
  });

  it("several groups asked from Explorer fill its stacked panel; its list link says the list shows one at a time", async () => {
    const user = await ask("Groceries and outings this year", groceriesAndOutingsThisYear);

    await waitFor(() => expect(location()).toBe("/analytics/explorer?stacked=group%3Agroceries%2Cgroup%3Aoutings&stackedRange=ytd"));
    expect(await words()).toBe("Understood as: Explorer · stacked: Groceries and Outings · year to date");
    await user.click(within(await line()).getByRole("link", { name: "See transactions →" }));

    await waitFor(() => expect(location()).toBe(`/transactions?from=${firstOfYear()}&to=${isoToday()}&direction=expense`));
    expect(within(await line()).getByText("the list shows one category at a time")).toBeInTheDocument();
  });

  it("several groups asked from the list stay on the list, which says it shows one at a time and offers Explorer", async () => {
    const user = await ask("Groceries and outings this year", { ...groceriesAndOutingsThisYear, target: "transactions" }, "/transactions");

    await waitFor(() => expect(location()).toBe(`/transactions?from=${firstOfYear()}&to=${isoToday()}&direction=expense`));
    expect(within(await line()).getByText("the list shows one category at a time")).toBeInTheDocument();
    await user.click(within(await line()).getByRole("link", { name: "Better in Explorer →" }));

    await waitFor(() => expect(location()).toBe("/analytics/explorer?stacked=group%3Agroceries%2Cgroup%3Aoutings&stackedRange=ytd"));
    expect(await words()).toBe("Understood as: Explorer · stacked: Groceries and Outings · year to date");
  });

  it("says what could not be applied next to what was", async () => {
    await ask("Restaurants last summer, by member", { ...restaurantsSummer, unsupported: [{ kind: "member", label: "member" }] });

    expect(await screen.findByText("Can't filter by member.")).toBeInTheDocument();
  });

  it("does not move when everything asked for is a filter that does not exist", async () => {
    await ask("What did Ana spend in Madrid?", {
      understood: true, target: null, reason: null, expressibleIn: [], filters: null,
      unsupported: [{ kind: "member", label: "member" }, { kind: "other", label: "city" }],
    }, "/transactions");

    expect(await screen.findByText("Can't filter by member or city, so nothing was applied.")).toBeInTheDocument();
    expect(location()).toBe("/transactions");
    expect(screen.queryByText("Understood as:")).not.toBeInTheDocument();
  });

  it("applies nothing when the question was not understood, leaving the manual filters as they were", async () => {
    await ask("Is it going to rain?", { understood: false, expressibleIn: [], unsupported: [] }, "/transactions?direction=expense");

    expect(await screen.findByText(/I didn't understand the question, so nothing was applied/)).toBeInTheDocument();
    expect(location()).toBe("/transactions?direction=expense");
    expect(fieldByLabel("Direction")).toHaveValue("expense");
  });

  it("is disabled while a question is processing", async () => {
    await ask("How much on restaurants?", () => new Promise(() => {}));

    expect(await box()).toBeDisabled();
    expect(screen.getByRole("button", { name: "Thinking…" })).toBeDisabled();
  });

  it("shows the server's refusal inline", async () => {
    await ask("How much on restaurants?", () =>
      Promise.reject({ response: { data: { code: "QUESTION_IN_PROGRESS", message: "busy" } } }),
    );

    expect(await screen.findByText("Your previous question is still being processed. Wait for it to finish.")).toBeInTheDocument();
  });

  it("says AI is unavailable when the provider did not answer — not that the question was not understood", async () => {
    await ask(
      "How much on restaurants?",
      () => Promise.reject({ response: { status: 502, data: { code: "QUESTION_PROVIDER_ERROR", message: "down" } } }),
      "/transactions?direction=expense",
    );

    expect(await screen.findByText(/AI is unavailable right now/)).toBeInTheDocument();
    expect(screen.queryByText(/I didn't understand the question/)).not.toBeInTheDocument();
    expect(location()).toBe("/transactions?direction=expense");
  });
});

describe("Ask: the cross-link", () => {
  it("on the list, an answer Explorer can show offers it, and the line follows there", async () => {
    const user = await ask("Restaurants last summer", { ...restaurantsSummer, target: "transactions" }, "/transactions");

    const link = within(await line()).getByRole("link", { name: "Better in Explorer →" });
    await user.click(link);

    await waitFor(() => expect(location()).toMatch(/^\/analytics\/explorer\?scope=category%3Aoutings\.restaurants/));
    expect(await words()).toBe("Understood as: Explorer · Outings → Restaurants & bars · Jun 2025 – Aug 2025 · no prior year");
    expect(within(await line()).getByRole("link", { name: "See transactions →" })).toBeInTheDocument();
  });

  it("on the list, an answer Explorer cannot show offers no link", async () => {
    await ask("How much income so far?", { ...incomeSoFarFromExplorer, reason: null }, "/transactions");

    await screen.findByText("Understood as:");
    expect(within(await line()).queryByRole("link")).not.toBeInTheDocument();
  });

  it("on Explorer, the answer always offers the transactions behind it", async () => {
    const user = await ask("How much on restaurants last summer?", restaurantsSummer);

    await user.click(within(await line()).getByRole("link", { name: "See transactions →" }));

    await waitFor(() =>
      expect(location()).toBe("/transactions?from=2025-06-01&to=2025-08-31&direction=expense&categoryCode=outings.restaurants"),
    );
    expect(await words()).toBe("Understood as: Transactions · expenses · Outings → Restaurants & bars · 1 Jun 2025 – 31 Aug 2025");
  });
});

describe("Ask: after the answer", () => {
  it("leaves every filter editable by hand, and clearing then keeps the hand edits", async () => {
    const user = await ask("How much on restaurants last summer?", restaurantsSummer);
    await screen.findByText("Understood as:");

    await user.selectOptions(fieldByLabel("Scope"), "category:groceries.groceries");
    expect(location()).toContain("scope=category%3Agroceries.groceries");

    await user.click(await clearButton());
    expect(screen.queryByText("Understood as:")).not.toBeInTheDocument();
    expect(location()).toContain("scope=category%3Agroceries.groceries");
  });

  it("clearing right away takes the question's filters off again", async () => {
    const user = await ask("How much on restaurants last summer?", restaurantsSummer, "/transactions");

    await user.click(await clearButton());

    expect(location()).toBe("/analytics/explorer");
    expect(screen.queryByText("Understood as:")).not.toBeInTheDocument();
  });
});
