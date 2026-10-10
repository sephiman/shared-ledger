import type { BankConnection, ConnectionStatus } from "@/api/banks";

/** The header banner and the card's urgency line start this many days before a consent expires. */
export const CONSENT_BANNER_WARNING_DAYS = 7;

export type ConsentUrgency = { kind: "ok" } | { kind: "expiring"; days: number } | { kind: "expired" };

// Same set the backend's Telegram notice watches: the credential states are fixed by an owner, not a re-link.
const WATCHED: ConnectionStatus[] = ["active", "expired", "suspended", "error"];

const MS_PER_DAY = 86_400_000;

function localMidnight(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

/** Calendar days in the viewer's zone, so "expires in 1 day" means tomorrow whatever the hour. */
export function consentUrgency(connection: BankConnection, now: Date): ConsentUrgency {
  if (!WATCHED.includes(connection.status)) return { kind: "ok" };
  // A sync can find the consent gone before its stored date (the bank revoked it early).
  if (connection.status === "expired") return { kind: "expired" };
  if (!connection.consentExpiresAt) return { kind: "ok" };
  const expiresAt = new Date(connection.consentExpiresAt);
  if (expiresAt.getTime() <= now.getTime()) return { kind: "expired" };
  const days = Math.round((localMidnight(expiresAt) - localMidnight(now)) / MS_PER_DAY);
  return days <= CONSENT_BANNER_WARNING_DAYS ? { kind: "expiring", days } : { kind: "ok" };
}

/** Identifies one consent cycle of one connection: a re-link moves the date, so a dismissal stops matching.
 *  The backend validates this exact shape. */
export function consentNoticeKey(connection: BankConnection): string {
  return `${connection.id}@${connection.consentExpiresAt?.slice(0, 10) ?? "none"}`;
}

export interface ConsentAlert {
  connection: BankConnection;
  urgency: Exclude<ConsentUrgency, { kind: "ok" }>;
}

/** Every connection that needs a nudge, dismissed or not. */
export function consentAlerts(connections: BankConnection[], now: Date): ConsentAlert[] {
  return connections.flatMap((connection) => {
    const urgency = consentUrgency(connection, now);
    return urgency.kind === "ok" ? [] : [{ connection, urgency }];
  });
}

/** The dismissal set to persist after hiding [alerts]: keeps only keys still in the window, so it never grows. */
export function dismissedAfterHiding(alerts: ConsentAlert[]): string[] {
  return alerts.map((a) => consentNoticeKey(a.connection));
}
