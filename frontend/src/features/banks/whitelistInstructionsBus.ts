/**
 * "Review instructions" in the Banks card has to open Phase B of the setup instructions, which live in the
 * credentials card — a sibling rendered only for owners. One listener is enough (a single card per page),
 * so this stays a one-slot bus rather than prop drilling through the settings page.
 */
type Listener = () => void;

let listener: Listener | null = null;

/** Registered by the credentials card; returns the unsubscribe for the effect cleanup. */
export function onShowWhitelistPhase(fn: Listener): () => void {
  listener = fn;
  return () => {
    if (listener === fn) listener = null;
  };
}

export function showWhitelistPhase(): void {
  listener?.();
}
