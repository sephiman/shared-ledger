import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RouteErrorBoundary } from "@/components/layout/RouteErrorBoundary";
import { installPreloadErrorRecovery, resetChunkReloadPending } from "@/lib/chunkRecovery";
import i18n from "@/i18n";

function Boom(): never {
  throw new Error("Failed to fetch dynamically imported module");
}

afterEach(async () => {
  cleanup();
  resetChunkReloadPending();
  vi.restoreAllMocks();
  await i18n.changeLanguage("en");
});

describe("RouteErrorBoundary", () => {
  it("renders a localized reload card when a route fails to load", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const user = userEvent.setup();
    const onReload = vi.fn();

    render(
      <RouteErrorBoundary resetKey="/analytics" pendingFallback={<p>Loading…</p>} onReload={onReload}>
        <Boom />
      </RouteErrorBoundary>,
    );

    expect(screen.getByText("Could not load this view")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reload page" }));
    expect(onReload).toHaveBeenCalledTimes(1);
  });

  it("renders the fallback in Spanish when locale is es", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    await i18n.changeLanguage("es");

    render(
      <RouteErrorBoundary resetKey="/analytics" pendingFallback={<p>Cargando…</p>} onReload={vi.fn()}>
        <Boom />
      </RouteErrorBoundary>,
    );

    expect(screen.getByText("No se pudo cargar esta vista")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Recargar página" })).toBeInTheDocument();
  });

  it("keeps showing the pending fallback while an automatic chunk reload is in flight", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const map = new Map<string, string>();
    const stop = installPreloadErrorRecovery({
      storage: {
        getItem: (k) => map.get(k) ?? null,
        setItem: (k, v) => void map.set(k, v),
      },
      reload: vi.fn(),
      now: () => 1_000_000,
    });
    window.dispatchEvent(new Event("vite:preloadError", { cancelable: true }));

    render(
      <RouteErrorBoundary resetKey="/analytics" pendingFallback={<p>Loading…</p>} onReload={vi.fn()}>
        <Boom />
      </RouteErrorBoundary>,
    );

    expect(screen.getByText("Loading…")).toBeInTheDocument();
    expect(screen.queryByText("Could not load this view")).not.toBeInTheDocument();
    stop();
  });

  it("recovers when resetKey changes to another route", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});

    const { rerender } = render(
      <RouteErrorBoundary resetKey="/analytics" pendingFallback={<p>Loading…</p>} onReload={vi.fn()}>
        <Boom />
      </RouteErrorBoundary>,
    );

    expect(screen.getByText("Could not load this view")).toBeInTheDocument();

    rerender(
      <RouteErrorBoundary resetKey="/dashboard" pendingFallback={<p>Loading…</p>} onReload={vi.fn()}>
        <p>Dashboard view</p>
      </RouteErrorBoundary>,
    );

    expect(screen.getByText("Dashboard view")).toBeInTheDocument();
  });
});
