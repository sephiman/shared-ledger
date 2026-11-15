import { afterEach, describe, expect, it, vi } from "vitest";
import { installPreloadErrorRecovery, isChunkReloadPending, resetChunkReloadPending } from "./chunkRecovery";

function createMemoryStorage(): Pick<Storage, "getItem" | "setItem"> {
  const map = new Map<string, string>();
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      map.set(key, value);
    },
  };
}

afterEach(() => {
  resetChunkReloadPending();
});

describe("chunkRecovery", () => {
  it("reloads once and prevents default when vite:preloadError fires", () => {
    const storage = createMemoryStorage();
    const reload = vi.fn();
    let now = 1_000_000;
    const stop = installPreloadErrorRecovery({ storage, reload, now: () => now });

    const event = new Event("vite:preloadError", { cancelable: true });
    window.dispatchEvent(event);

    expect(reload).toHaveBeenCalledTimes(1);
    expect(event.defaultPrevented).toBe(true);
    expect(isChunkReloadPending()).toBe(true);

    stop();
  });

  it("suppresses a second reload within the cooldown window to avoid a reload loop", () => {
    const storage = createMemoryStorage();
    const reload = vi.fn();
    let now = 1_000_000;
    const stop = installPreloadErrorRecovery({ storage, reload, now: () => now });

    window.dispatchEvent(new Event("vite:preloadError", { cancelable: true }));
    resetChunkReloadPending();

    now += 5_000;
    const second = new Event("vite:preloadError", { cancelable: true });
    window.dispatchEvent(second);

    expect(reload).toHaveBeenCalledTimes(1);
    expect(second.defaultPrevented).toBe(false);
    expect(isChunkReloadPending()).toBe(false);

    stop();
  });

  it("allows another reload after the cooldown window expires", () => {
    const storage = createMemoryStorage();
    const reload = vi.fn();
    let now = 1_000_000;
    const stop = installPreloadErrorRecovery({ storage, reload, now: () => now });

    window.dispatchEvent(new Event("vite:preloadError", { cancelable: true }));
    resetChunkReloadPending();

    now += 15_000;
    window.dispatchEvent(new Event("vite:preloadError", { cancelable: true }));

    expect(reload).toHaveBeenCalledTimes(2);

    stop();
  });

  it("does not auto-reload when sessionStorage is unavailable or throws", () => {
    const reload = vi.fn();
    const throwingStorage: Pick<Storage, "getItem" | "setItem"> = {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("blocked");
      },
    };
    const stop = installPreloadErrorRecovery({ storage: throwingStorage, reload });

    window.dispatchEvent(new Event("vite:preloadError", { cancelable: true }));

    expect(reload).not.toHaveBeenCalled();
    expect(isChunkReloadPending()).toBe(false);

    stop();
  });

  it("removes its listener when the returned cleanup runs", () => {
    const storage = createMemoryStorage();
    const reload = vi.fn();
    const stop = installPreloadErrorRecovery({ storage, reload });
    stop();

    window.dispatchEvent(new Event("vite:preloadError", { cancelable: true }));

    expect(reload).not.toHaveBeenCalled();
  });
});
