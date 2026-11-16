import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { AxiosResponse } from "axios";
import { apiClient } from "@/api/client";
import { AuthProvider, useAuth, type Me } from "@/auth/AuthContext";
import i18n from "@/i18n";
import { SettingsPage } from "./SettingsPage";

const ok = <T,>(data: T) => Promise.resolve({ data } as AxiosResponse<T>);

interface StubOptions {
  role?: "owner" | "member";
  banksConfigured?: boolean;
  connectionCount?: number;
  ai?: boolean;
}

function makeUser(role: "owner" | "member"): Me {
  return {
    id: "u1",
    email: "user@example.com",
    locale: "en",
    defaultHouseholdId: "h1",
    hiddenHomePanels: [],
    portfolioReturnBasis: "OPEN_COST",
    dismissedConsentNotices: [],
    households: [{ householdId: "h1", name: "Home", currency: "EUR", role }],
  };
}

function stubApi({ role = "owner", banksConfigured = true, connectionCount = 1, ai = true }: StubOptions = {}) {
  const user = makeUser(role);
  vi.spyOn(apiClient, "get").mockImplementation((url: string) => {
    if (url === "/auth/csrf") return ok({});
    if (url === "/auth/me") return ok(user);
    if (url === "/auth/features") return ok({ passwordReset: true, emailChangeVerified: true });
    if (url === "/features") return ok({ ai });
    if (url === "/households/h1") return ok({ id: "h1", name: "Home", currency: "EUR", defaultLocale: "en" });
    if (url === "/households/h1/members") {
      return ok([{ userId: "u1", email: "user@example.com", role, joinedAt: "2026-01-01T00:00:00Z" }]);
    }
    if (url === "/households/h1/invitations") return ok([]);
    if (url === "/households/h1/categories") return ok([]);
    if (url === "/households/h1/snapshots/auto-settings") return ok({ enabled: false, frequency: "monthly" });
    if (url === "/households/h1/telegram-settings") {
      return ok({
        active: false,
        notifyTransactions: true,
        notifySnapshots: true,
        notifyMovements: true,
        notifyLendingPayments: true,
        notifyHoldings: true,
        notifyRecurringTxn: true,
        notifyRecurringLending: true,
        notifyBankMovements: true,
        notifyBankConnections: true,
        chatId: null,
        tokenConfigured: false,
      });
    }
    if (url === "/households/h1/banks/config") {
      return ok({ credentialsConfigured: banksConfigured, connectionCount, nextSyncTimes: [] });
    }
    if (url === "/households/h1/banks/credentials") {
      return ok({ appId: null, privateKeyConfigured: false, redirectUrl: null, mismatchedConnectionCount: 0 });
    }
    if (url === "/households/h1/banks/connections") return ok([]);
    if (url === "/households/h1/banks/aspsps") return ok([]);
    if (url === "/households/h1/banks/sync-schedule") {
      return ok({ times: ["07:00"], timezone: "UTC", isDefault: true });
    }
    if (url === "/households/h1/ai-suggestions/settings") {
      return ok({ enabled: false, model: "test-model", providerHost: "ai.test" });
    }
    throw new Error(`unexpected GET ${url}`);
  });
}

function ReadySettings() {
  const { user, activeHouseholdId } = useAuth();
  return user && activeHouseholdId ? <SettingsPage /> : null;
}

function renderSettings(initialEntry = "/settings", options?: StubOptions) {
  stubApi(options);
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <AuthProvider>
          <ReadySettings />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const originalScrollIntoView = Element.prototype.scrollIntoView;

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(async () => {
  cleanup();
  localStorage.clear();
  Element.prototype.scrollIntoView = originalScrollIntoView;
  window.history.replaceState(null, "", "/");
  vi.restoreAllMocks();
  await i18n.changeLanguage("en");
});

describe("SettingsPage anchoring", () => {
  it("renders a jump-to contents list where every link targets a matching section card on the page", async () => {
    renderSettings();

    const nav = await screen.findByRole("navigation", { name: "Jump to section" });
    await waitFor(() => {
      expect(document.getElementById("ai-features")).not.toBeNull();
      expect(document.getElementById("household-settings")).not.toBeNull();
    });

    const links = within(nav).getAllByRole("link");
    expect(links.length).toBe(17);

    for (const link of links) {
      const href = link.getAttribute("href");
      expect(href).toMatch(/^#[a-z-]+$/);
      const target = document.getElementById(href!.slice(1));
      expect(target, `missing anchor target for ${href}`).not.toBeNull();
    }

    expect(within(nav).getByRole("link", { name: "Banks" })).toHaveAttribute("href", "#banks");
    expect(within(nav).getByRole("link", { name: "Danger zone" })).toHaveAttribute("href", "#danger-zone");
  });

  it("hides owner-only and unconfigured sections from the contents list", async () => {
    renderSettings("/settings", { role: "member", banksConfigured: false, connectionCount: 0, ai: false });

    const nav = await screen.findByRole("navigation", { name: "Jump to section" });
    await waitFor(() => expect(document.getElementById("household-settings")).not.toBeNull());

    expect(within(nav).queryByRole("link", { name: "Notifications" })).not.toBeInTheDocument();
    expect(within(nav).queryByRole("link", { name: "Banks" })).not.toBeInTheDocument();
    expect(within(nav).queryByRole("link", { name: "AI features" })).not.toBeInTheDocument();
    expect(within(nav).queryByRole("link", { name: "Invitations" })).not.toBeInTheDocument();
    expect(within(nav).queryByRole("link", { name: "Danger zone" })).not.toBeInTheDocument();

    for (const link of within(nav).getAllByRole("link")) {
      const id = link.getAttribute("href")!.slice(1);
      expect(document.getElementById(id)).not.toBeNull();
    }
  });

  it("scrolls to a deep-linked section hash once that section mounts", async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    renderSettings("/settings#banks");

    await waitFor(() => {
      const banksCard = document.getElementById("banks");
      expect(banksCard).not.toBeNull();
      expect(scrollIntoView).toHaveBeenCalled();
      expect(scrollIntoView.mock.instances).toContain(banksCard);
    });
  });

  it("supports Spanish labels and back-to-top scrolling", async () => {
    await i18n.changeLanguage("es");
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const user = userEvent.setup();

    renderSettings();

    expect(await screen.findByRole("navigation", { name: "Ir a la sección" })).toBeInTheDocument();
    expect(screen.getByText("Índice")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Volver arriba" }));
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: "smooth", block: "start" });
  });

  it("scrolls only the nearest overflow-y scroll container and clamps near the bottom", async () => {
    const user = userEvent.setup();
    stubApi();
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const scrollTo = vi.fn();

    render(
      <div data-testid="scroll-container" style={{ overflowY: "auto", height: "400px" }}>
        <QueryClientProvider client={qc}>
          <MemoryRouter initialEntries={["/settings"]}>
            <AuthProvider>
              <ReadySettings />
            </AuthProvider>
          </MemoryRouter>
        </QueryClientProvider>
      </div>,
    );

    const container = screen.getByTestId("scroll-container");
    container.scrollTo = scrollTo;
    Object.defineProperty(container, "scrollHeight", { configurable: true, value: 2000 });
    Object.defineProperty(container, "clientHeight", { configurable: true, value: 400 });

    const nav = await screen.findByRole("navigation", { name: "Jump to section" });
    await waitFor(() => expect(document.getElementById("danger-zone")).not.toBeNull());

    const dz = document.getElementById("danger-zone")!;
    vi.spyOn(container, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 100, 800, 400));
    vi.spyOn(dz, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 2200, 800, 140));

    await user.click(within(nav).getByRole("link", { name: "Danger zone" }));

    expect(scrollTo).toHaveBeenCalledWith({ top: 1600, behavior: "smooth" });
    expect(window.location.hash).toBe("#danger-zone");

    await user.click(screen.getByRole("button", { name: "Back to top" }));
    expect(scrollTo).toHaveBeenLastCalledWith({ top: 0, behavior: "smooth" });
  });
});
