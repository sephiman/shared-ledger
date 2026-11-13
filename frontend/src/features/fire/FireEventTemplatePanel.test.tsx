import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import i18n from "@/i18n";
import type { FireProjection } from "@/api/fire";
import { FireEventTemplatePanel } from "./FireEventTemplatePanel";
import { makeProjection } from "./fireTestProjection";

function withReferenceStats(year: number | null, probability: number): FireProjection {
  const p = makeProjection();
  return {
    ...p,
    scenarios: p.scenarios.map((s) => ({
      ...s,
      tierStats: s.tierStats.map((ts) => (ts.tier === "fire" ? { ...ts, estimatedYear: year, probabilityOfReachingTarget: probability } : ts)),
    })),
  };
}

describe("FireEventTemplatePanel", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("en");
  });

  it("prefills the mortgage from the category, previews the effect and adds exactly that event", async () => {
    const user = userEvent.setup();
    const onPreview = vi.fn();
    const onAdd = vi.fn();
    const baseline = withReferenceStats(2046, 0.56);
    const { rerender } = render(
      <FireEventTemplatePanel
        baseline={baseline}
        currentEvents={[]}
        suggestions={{ mortgageMonthly: "650.00", windowMonths: 12 }}
        currency="EUR"
        onPreview={onPreview}
        before={baseline}
        after={undefined}
        previewPending={false}
        onAdd={onAdd}
        onCancel={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("radio", { name: "The mortgage ends" }));
    expect(screen.getByLabelText(/How much is it a month/)).toHaveValue(650);
    expect(screen.getByText(/In today's money/)).toBeInTheDocument();
    expect(screen.getByText(/Suggested from your Mortgage category/)).toBeInTheDocument();

    const year = screen.getByLabelText("In which year does it end?");
    await user.clear(year);
    await user.type(year, "2038");
    const expected = [{ year: 2038, kind: "spending_delta", amount: "-650.00", label: "The mortgage ends", template: "mortgage_end" }];
    await waitFor(() => expect(onPreview).toHaveBeenLastCalledWith(expected));

    rerender(
      <FireEventTemplatePanel
        baseline={baseline}
        currentEvents={[]}
        suggestions={{ mortgageMonthly: "650.00", windowMonths: 12 }}
        currency="EUR"
        onPreview={onPreview}
        before={baseline}
        after={withReferenceStats(2044, 0.71)}
        previewPending={false}
        onAdd={onAdd}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByTestId("fire-template-preview")).toHaveTextContent("2046 → 2044 · chance 56% → 71%");

    await user.click(screen.getByRole("button", { name: "Add to events" }));
    expect(onAdd).toHaveBeenCalledWith(expected);
  });

  it("leaves the mortgage blank when the category has no data, and gives pension guidance", async () => {
    const user = userEvent.setup();
    const baseline = makeProjection();
    render(
      <FireEventTemplatePanel
        baseline={baseline}
        currentEvents={[]}
        suggestions={{ mortgageMonthly: null, windowMonths: 12 }}
        currency="EUR"
        onPreview={vi.fn()}
        before={baseline}
        after={undefined}
        previewPending={false}
        onAdd={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    await user.click(screen.getByRole("radio", { name: "The mortgage ends" }));
    expect(screen.getByLabelText(/How much is it a month/)).toHaveValue(null);
    expect(screen.getByText(/has no spending in the current window/)).toBeInTheDocument();

    await user.click(screen.getByRole("radio", { name: "The state pension starts" }));
    expect(screen.getByLabelText(/How much a month, after tax/)).toHaveValue(null);
    expect(screen.getByText(/pension statement/)).toBeInTheDocument();
  });

  it("does not let a one-off amount land in a year the projection does not simulate", async () => {
    const user = userEvent.setup();
    const onAdd = vi.fn();
    const baseline = makeProjection(); // projection starts in 2026, so one-off amounts count from 2027
    render(
      <FireEventTemplatePanel
        baseline={baseline}
        currentEvents={[]}
        suggestions={undefined}
        currency="EUR"
        onPreview={vi.fn()}
        before={baseline}
        after={undefined}
        previewPending={false}
        onAdd={onAdd}
        onCancel={vi.fn()}
      />,
    );
    await user.click(screen.getByRole("radio", { name: "A big one-off expense" }));
    expect(screen.getByLabelText("In which year?")).toHaveValue(2027);
    await user.type(screen.getByLabelText(/How much will it cost/), "25000");
    const year = screen.getByLabelText("In which year?");
    await user.clear(year);
    await user.type(year, "2026");
    expect(screen.getByRole("button", { name: "Add to events" })).toBeDisabled();
    expect(screen.getAllByText(/One-off amounts count from 2027/).length).toBeGreaterThan(0);

    await user.clear(year);
    await user.type(year, "2028");
    await user.click(screen.getByRole("button", { name: "Add to events" }));
    expect(onAdd).toHaveBeenCalledWith([
      { year: 2028, kind: "lump_sum", amount: "-25000.00", label: "A big one-off expense", template: "big_expense" },
    ]);
  });
});
