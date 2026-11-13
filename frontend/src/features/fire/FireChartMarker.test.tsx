import { cloneElement, type ReactElement } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import i18n from "@/i18n";
import type { FireProjection } from "@/api/fire";
import { FireChart } from "./FireChart";
import { makeProjection } from "./fireTestProjection";

// jsdom measures every container at 0×0, so the responsive wrapper would never draw the chart.
vi.mock("recharts", async (importOriginal) => ({
  ...(await importOriginal<typeof import("recharts")>()),
  ResponsiveContainer: ({ children }: { children: ReactElement<{ width?: number; height?: number }> }) =>
    cloneElement(children, { width: 800, height: 384 }),
}));

const YEARS = Array.from({ length: 21 }, (_, i) => 2026 + i);

/** Twenty-year paths so the marker years fall on the chart's x axis. */
function chartProjection(): FireProjection {
  const p = makeProjection();
  for (const sc of p.scenarios) {
    sc.percentiles = YEARS.map((year, i) => {
      const v = String(150_000 + i * 30_000);
      return { year, p10: v, p25: v, p50: v, p75: v, p90: v };
    });
    sc.tierStats = sc.tierStats.map((st) => ({
      ...st,
      drawdownPercentiles: [{ year: 0, p10: "1", p25: "1", p50: "1", p75: "1", p90: "1" }],
    }));
  }
  for (const tier of p.tiers) {
    tier.targetCurve = YEARS.map((year) => ({ year, value: tier.targetToday ?? "0" }));
  }
  // Reference cell = Base/FIRE (2039); Conservative/FIRE would sit at 2043.
  return p;
}

const MARKER = "half of the simulations have got there";

function markerLabel(container: HTMLElement): string | null {
  const label = container.querySelector(".chart-marker-label");
  return label ? [...label.querySelectorAll("tspan")].map((line) => line.textContent).join(" ") : null;
}

function markers(container: HTMLElement) {
  return container.querySelectorAll(".recharts-reference-line.chart-marker");
}

describe("FireChart estimated-year marker", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("en");
  });

  it("marks the reference cell's estimated year in both money modes", async () => {
    const user = userEvent.setup();
    const { container } = render(<FireChart projection={chartProjection()} currency="EUR" />);
    expect(markerLabel(container)).toBe(MARKER);
    expect(markers(container)).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Today's buying power" }));
    expect(markerLabel(container)).toBe(MARKER);
  });

  it("is hidden when the reference cell has no year", () => {
    const p = chartProjection();
    p.scenarios[1].tierStats[1] = { ...p.scenarios[1].tierStats[1], estimatedYear: null, probabilityOfReachingTarget: 0.4 };
    const { container } = render(<FireChart projection={p} currency="EUR" />);
    expect(markerLabel(container)).toBeNull();
    expect(markers(container)).toHaveLength(0);
  });

  it("is hidden in the drawdown phase", async () => {
    const user = userEvent.setup();
    const { container } = render(<FireChart projection={chartProjection()} currency="EUR" />);
    expect(markers(container)).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: /^Drawdown/ }));
    expect(markers(container)).toHaveLength(0);
    expect(markerLabel(container)).toBeNull();
  });

  it("shows only the reference marker when another scenario is toggled on", async () => {
    const user = userEvent.setup();
    const p = chartProjection();
    // Without the reference year there must be no marker, even with Conservative (2043) visible.
    p.scenarios[1].tierStats[1] = { ...p.scenarios[1].tierStats[1], estimatedYear: null, probabilityOfReachingTarget: 0.4 };
    const { container, rerender } = render(<FireChart projection={p} currency="EUR" />);
    await user.click(screen.getByRole("button", { name: "Conservative" }));
    expect(markers(container)).toHaveLength(0);

    rerender(<FireChart projection={chartProjection()} currency="EUR" />);
    expect(markers(container)).toHaveLength(1);
  });
});
