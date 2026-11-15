const RELOAD_STORAGE_KEY = "shared-ledger:chunk-reload-at";
const RELOAD_COOLDOWN_MS = 15_000;

let reloadPending = false;

export interface PreloadRecoveryDeps {
  target?: Pick<Window, "addEventListener" | "removeEventListener">;
  storage?: Pick<Storage, "getItem" | "setItem"> | null;
  reload?: () => void;
  now?: () => number;
}

function defaultStorage(): Pick<Storage, "getItem" | "setItem"> | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

export function isChunkReloadPending(): boolean {
  return reloadPending;
}

export function resetChunkReloadPending(): void {
  reloadPending = false;
}

/** Reload once when a stale bundle references a chunk removed by a newer deployment. */
export function handlePreloadError(event: Event, deps: Omit<PreloadRecoveryDeps, "target"> = {}): boolean {
  const storage = deps.storage === undefined ? defaultStorage() : deps.storage;
  const now = (deps.now ?? Date.now)();
  if (!storage) return false;

  try {
    const raw = storage.getItem(RELOAD_STORAGE_KEY);
    if (raw !== null) {
      const lastAt = Number(raw);
      if (Number.isFinite(lastAt) && now - lastAt < RELOAD_COOLDOWN_MS) {
        return false;
      }
    }
    storage.setItem(RELOAD_STORAGE_KEY, String(now));
  } catch {
    return false;
  }

  reloadPending = true;
  event.preventDefault();
  (deps.reload ?? (() => window.location.reload()))();
  return true;
}

export function installPreloadErrorRecovery(deps: PreloadRecoveryDeps = {}): () => void {
  const target = deps.target ?? window;
  const listener = (event: Event) => {
    handlePreloadError(event, deps);
  };
  target.addEventListener("vite:preloadError", listener);
  return () => target.removeEventListener("vite:preloadError", listener);
}
