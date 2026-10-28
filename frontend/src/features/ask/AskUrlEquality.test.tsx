import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, configure, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import i18n from "@/i18n";
import type { QuestionAnswer } from "@/api/questions";
import { fieldByLabel } from "@/test/LocationProbe";
import { lastRequest, stubLedgerApi } from "@/test/ledgerApi";
import { renderViews } from "@/test/renderViews";
import { setAskResult } from "./askResultStore";

/** Setting filters by hand and asking for the same thing must be indistinguishable: same URL, same backend request,
 *  whether the question lands on a view directly or the member follows the cross-link to it. */

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", currency: "EUR", role: "owner" }),
}));

// Whole views render twice per case; under a loaded parallel run they need more than the defaults.
configure({ asyncUtilTimeout: 4000 });
vi.setConfig({ testTimeout: 20000 });

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

beforeEach(() => {
  sessionStorage.clear();
  setAskResult(null);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const location = () => screen.getByTestId("location").textContent ?? "";

/** Restaurants, Jun–Aug 2025: a filter object both views can show. */
const answer = (target: "explorer" | "transactions"): QuestionAnswer => ({
  understood: true,
  target,
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
});

async function askFrom(url: string, reply: QuestionAnswer) {
  stubLedgerApi(() => reply);
  const user = userEvent.setup();
  renderViews(url);
  await user.type(await screen.findByRole("textbox", { name: "Ask" }), "anything{Enter}");
  return user;
}

async function followLink(user: ReturnType<typeof userEvent.setup>, name: string) {
  const status = (await screen.findByText("Understood as:")).closest("[role=status]") as HTMLElement;
  await user.click(within(status).getByRole("link", { name }));
}

async function explorerByHand() {
  stubLedgerApi();
  const user = userEvent.setup();
  const view = renderViews("/analytics/explorer");
  await screen.findByRole("option", { name: /Restaurants/ });
  await user.selectOptions(fieldByLabel("Scope"), "category:outings.restaurants");
  await user.selectOptions(fieldByLabel("Range"), "custom");
  fireEvent.change(fieldByLabel("From"), { target: { value: "2025-06-01" } });
  fireEvent.change(fieldByLabel("To"), { target: { value: "2025-08-31" } });
  await user.click(await screen.findByRole("checkbox", { name: "Overlay same period one year earlier" }));
  await waitFor(() => expect(lastRequest("/analytics/explorer")).toMatchObject({ from: "2025-06-01", yoyOverlay: false }));
  const result = { url: location(), request: lastRequest("/analytics/explorer") };
  view.unmount();
  sessionStorage.clear();
  return result;
}

async function transactionsByHand() {
  stubLedgerApi();
  const user = userEvent.setup();
  const view = renderViews("/transactions");
  await screen.findByRole("option", { name: /Restaurants/ });
  fireEvent.change(fieldByLabel("From"), { target: { value: "2025-06-01" } });
  fireEvent.change(fieldByLabel("To"), { target: { value: "2025-08-31" } });
  await user.selectOptions(fieldByLabel("Direction"), "expense");
  await user.selectOptions(fieldByLabel("Category"), "outings.restaurants");
  await waitFor(() => expect(lastRequest("/transactions")).toMatchObject({ categoryCode: "outings.restaurants", from: "2025-06-01" }));
  const result = { url: location(), request: lastRequest("/transactions") };
  view.unmount();
  return result;
}

async function stackedByHand() {
  stubLedgerApi();
  const user = userEvent.setup();
  const view = renderViews("/analytics/explorer");
  await screen.findByRole("option", { name: /Restaurants/ });
  // The group rows come before their categories, so the first match is the group.
  await user.click((await screen.findAllByRole("checkbox", { name: /Groceries/ }))[0]);
  await user.click(screen.getByRole("checkbox", { name: /Outings/ }));
  await user.selectOptions(fieldByLabel("Range", 1), "ytd");
  await waitFor(() => expect(location()).toContain("stackedRange=ytd"));
  const result = { url: location(), request: lastRequest("/analytics/heatmap") };
  view.unmount();
  sessionStorage.clear();
  return result;
}

const stackedAnswer: QuestionAnswer = {
  understood: true,
  target: "transactions",
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

describe("a question applies filters exactly as the manual controls do", () => {
  it("following “Better in Explorer →” with several groups, into the stacked panel", async () => {
    const byHand = await stackedByHand();

    const user = await askFrom("/transactions", stackedAnswer);
    await followLink(user, "Better in Explorer →");

    await waitFor(() => expect(location()).toBe(byHand.url));
    await waitFor(() => expect(lastRequest("/analytics/heatmap")).toEqual(byHand.request));
  });

  it("landing on Explorer", async () => {
    const byHand = await explorerByHand();

    await askFrom("/analytics/explorer", answer("explorer"));

    await waitFor(() => expect(location()).toBe(byHand.url));
    await waitFor(() => expect(lastRequest("/analytics/explorer")).toEqual(byHand.request));
  });

  it("landing on the transactions list", async () => {
    const byHand = await transactionsByHand();

    await askFrom("/transactions", answer("transactions"));

    await waitFor(() => expect(location()).toBe(byHand.url));
    await waitFor(() => expect(lastRequest("/transactions")).toEqual(byHand.request));
  });

  it("following “Better in Explorer →” from the list", async () => {
    const byHand = await explorerByHand();

    const user = await askFrom("/transactions", answer("transactions"));
    await followLink(user, "Better in Explorer →");

    await waitFor(() => expect(location()).toBe(byHand.url));
    await waitFor(() => expect(lastRequest("/analytics/explorer")).toEqual(byHand.request));
  });

  it("following “See transactions →” from Explorer", async () => {
    const byHand = await transactionsByHand();

    const user = await askFrom("/analytics/explorer", answer("explorer"));
    await followLink(user, "See transactions →");

    await waitFor(() => expect(location()).toBe(byHand.url));
    await waitFor(() => expect(lastRequest("/transactions")).toEqual(byHand.request));
  });
});
