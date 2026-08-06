import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider, useTheme, type ThemePreference } from "@/lib/theme";

/** Controllable replacement for the fixed `matches: false` stub in test/setup.ts: lets a test flip the
 *  OS preference and fire the change event the provider subscribes to. */
function stubMatchMedia(prefersDark: boolean) {
  const handlers = new Set<() => void>();
  const mql = {
    get matches() {
      return prefersDark;
    },
    media: "(prefers-color-scheme: dark)",
    onchange: null,
    addEventListener: (_: string, h: () => void) => handlers.add(h),
    removeEventListener: (_: string, h: () => void) => handlers.delete(h),
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  };
  window.matchMedia = (() => mql) as unknown as typeof window.matchMedia;
  return {
    /** Emulates the user changing their OS appearance while the app is open. */
    setSystemDark(v: boolean) {
      prefersDark = v;
      act(() => handlers.forEach((h) => h()));
    },
    get listenerCount() {
      return handlers.size;
    },
  };
}

function Probe() {
  const { theme, resolvedTheme, setTheme } = useTheme();
  return (
    <>
      <p data-testid="theme">{theme}</p>
      <p data-testid="resolved">{resolvedTheme}</p>
      {(["light", "dark", "oled", "system"] as ThemePreference[]).map((t) => (
        <button key={t} onClick={() => setTheme(t)}>
          {t}
        </button>
      ))}
    </>
  );
}

const classes = () => Array.from(document.documentElement.classList);
const themeColor = () => document.querySelector('meta[name="theme-color"]')?.getAttribute("content");
const resolved = () => screen.getByTestId("resolved").textContent;

beforeEach(() => {
  localStorage.clear();
  document.documentElement.className = "";
  const meta = document.createElement("meta");
  meta.setAttribute("name", "theme-color");
  meta.setAttribute("content", "#0ea5e9");
  document.head.appendChild(meta);
});

afterEach(() => {
  cleanup();
  document.querySelector('meta[name="theme-color"]')?.remove();
});

describe("theme preference resolution", () => {
  it("resolves system to light or dark from the OS, and never to oled", () => {
    stubMatchMedia(false);
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(resolved()).toBe("light");
    expect(classes()).toEqual([]);

    cleanup();
    stubMatchMedia(true);
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(resolved()).toBe("dark");
    expect(classes()).toEqual(["dark"]);
  });

  it("keeps oled fixed regardless of the OS preference", () => {
    localStorage.setItem("theme", "oled");
    stubMatchMedia(false);
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );

    expect(screen.getByTestId("theme").textContent).toBe("oled");
    expect(resolved()).toBe("oled");
  });

  it("falls back to system for a stored value it does not recognise", () => {
    localStorage.setItem("theme", "midnight");
    stubMatchMedia(true);
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );

    expect(screen.getByTestId("theme").textContent).toBe("system");
    expect(resolved()).toBe("dark");
  });
});

describe("theme application to the document", () => {
  it("gives oled both classes, so dark: variants still apply and only surfaces are re-pointed", async () => {
    const user = userEvent.setup();
    stubMatchMedia(false);
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );

    await user.click(screen.getByRole("button", { name: "oled" }));
    expect(classes().sort()).toEqual(["dark", "oled"]);
  });

  it("drops the oled class when switching to dark, and both when switching to light", async () => {
    const user = userEvent.setup();
    stubMatchMedia(false);
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );

    await user.click(screen.getByRole("button", { name: "oled" }));
    await user.click(screen.getByRole("button", { name: "dark" }));
    expect(classes()).toEqual(["dark"]);

    await user.click(screen.getByRole("button", { name: "light" }));
    expect(classes()).toEqual([]);
  });

  it("blackens the mobile address bar under oled only", async () => {
    const user = userEvent.setup();
    stubMatchMedia(true);
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(themeColor()).toBe("#0ea5e9");

    await user.click(screen.getByRole("button", { name: "oled" }));
    expect(themeColor()).toBe("#000000");

    await user.click(screen.getByRole("button", { name: "dark" }));
    expect(themeColor()).toBe("#0ea5e9");
  });

  it("persists the choice per browser", async () => {
    const user = userEvent.setup();
    stubMatchMedia(false);
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );

    await user.click(screen.getByRole("button", { name: "oled" }));
    expect(localStorage.getItem("theme")).toBe("oled");
  });
});

describe("live reaction to OS appearance changes", () => {
  it("follows the OS under system, still only ever landing on light or dark", () => {
    const mql = stubMatchMedia(false);
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
    expect(resolved()).toBe("light");

    mql.setSystemDark(true);
    expect(resolved()).toBe("dark");
    expect(classes()).toEqual(["dark"]);

    mql.setSystemDark(false);
    expect(resolved()).toBe("light");
    expect(classes()).toEqual([]);
  });

  it("ignores the OS once oled is chosen explicitly", async () => {
    const user = userEvent.setup();
    const mql = stubMatchMedia(true);
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );

    await user.click(screen.getByRole("button", { name: "oled" }));
    expect(mql.listenerCount).toBe(0);

    mql.setSystemDark(false);
    expect(resolved()).toBe("oled");
    expect(classes().sort()).toEqual(["dark", "oled"]);
  });
});
