import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import i18n from "@/i18n";

const requests = vi.hoisted(() => ({ params: [] as Record<string, unknown>[] }));

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", name: "Home", currency: "EUR", role: "owner" }),
}));
vi.mock("@/api/catalog", () => ({ useAssetClasses: () => ({ data: [] }) }));
vi.mock("@/api/networth", () => {
  const mutation = { mutate: vi.fn(), isPending: false };
  return {
    useMovements: (_h: string, params: Record<string, unknown>) => {
      requests.params.push(params);
      return { data: { items: [], total: 0, page: 0, size: 100 } };
    },
    useLiabilities: () => ({ data: [] }),
    useCreateMovement: () => mutation,
    useUpdateMovement: () => mutation,
    useDeleteMovement: () => mutation,
  };
});

const { MovementsTab } = await import("./MovementsTab");

const renderAt = (url: string) =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <MovementsTab />
    </MemoryRouter>,
  );

beforeEach(() => {
  requests.params = [];
});

afterEach(async () => {
  cleanup();
  await i18n.changeLanguage("en");
});

describe("movements deep link", () => {
  it("narrows the list to the linked class, type and period, and clears back to everything", async () => {
    const user = userEvent.setup();
    renderAt("/networth?tab=flows&flow=movements&type=contribution&class=etfs&from=2025-03-01&to=2025-03-31");

    expect(requests.params.at(-1)).toEqual({
      size: 100,
      type: "contribution",
      assetClassCode: "etfs",
      from: "2025-03-01",
      to: "2025-03-31",
    });
    expect(screen.getByText(/^Showing contributions · ETFs · /)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Show all movements" }));

    expect(requests.params.at(-1)).toEqual({ size: 100 });
    expect(screen.queryByText(/^Showing/)).toBeNull();
  });

  it("names a debt payment filter in lower case, like the other types", () => {
    renderAt("/networth?tab=flows&flow=movements&type=debt_payment");

    expect(screen.getByText("Showing debt payments")).toBeInTheDocument();
  });

  it("ignores malformed filter values", () => {
    renderAt("/networth?tab=flows&flow=movements&type=bogus&from=yesterday");

    expect(requests.params.at(-1)).toEqual({ size: 100 });
    expect(screen.queryByText(/^Showing/)).toBeNull();
  });
});
