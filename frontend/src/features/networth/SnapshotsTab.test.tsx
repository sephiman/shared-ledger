import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SnapshotsTab } from "@/features/networth/SnapshotsTab";
import type { Snapshot } from "@/api/networth";
import i18n from "@/i18n";

let snapshotsData: Snapshot[] = [];

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", name: "Home", currency: "EUR", role: "owner" }),
}));

vi.mock("@/api/catalog", () => ({
  useAssetClasses: () => ({ data: [{ code: "cash", sortOrder: 10 }] }),
}));

vi.mock("@/api/portfolio", () => ({
  usePortfolioValuation: () => ({ data: undefined }),
}));

vi.mock("@/api/networth", () => ({
  useSnapshots: () => ({ data: snapshotsData }),
  useSnapshotPrefill: () => ({ data: { previous: null, activeLiabilities: [] } }),
  useLiabilities: () => ({ data: [] }),
  useAssets: () => ({ data: [] }),
  useNamedValuesAt: () => ({ data: undefined }),
  useCreateSnapshot: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useUpdateSnapshot: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useDeleteSnapshot: () => ({ mutate: vi.fn(), isPending: false }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  snapshotsData = [];
});

describe("SnapshotsTab detail", () => {
  it("shows a non-counted liability with its value and a not-counted badge, not hidden", async () => {
    snapshotsData = [
      {
        id: "snap-1",
        snapshotDate: "2026-11-20",
        note: null,
        totalAssets: "10000.00",
        totalLiabilities: "3000.00",
        netWorth: "7000.00",
        assets: [{ assetClassCode: "cash", value: "10000.00", valueSource: "overridden" }],
        namedAssets: [],
        liabilities: [
          { liabilityId: "l1", liabilityName: "Credito Ele", balance: "1000.00", countsTowardNetWorth: false },
          { liabilityId: "l2", liabilityName: "Mortgage", balance: "3000.00", countsTowardNetWorth: true },
        ],
        createdAt: "2026-11-20T10:00:00Z",
      } as Snapshot,
    ];

    const user = userEvent.setup();
    render(<SnapshotsTab />);

    // Expand the snapshot row in the desktop table.
    const table = screen.getByRole("table");
    await user.click(within(table).getByText(/20 Nov 2026|Nov 20, 2026/i));

    expect(within(table).getByText("Credito Ele")).toBeInTheDocument();
    expect(within(table).getByText("Mortgage")).toBeInTheDocument();
    expect(within(table).getByText("€1,000.00")).toBeInTheDocument();
    expect(within(table).getByText("Not counted")).toBeInTheDocument();
  });
});
