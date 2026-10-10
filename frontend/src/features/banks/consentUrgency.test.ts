import { describe, expect, it } from "vitest";
import type { BankConnection } from "@/api/banks";
import { consentAlerts, consentNoticeKey, consentUrgency, dismissedAfterHiding } from "./consentUrgency";

// A fixed local "now" at 09:00, so day arithmetic never depends on when the suite runs.
const NOW = new Date(2026, 9, 10, 9, 0);

function at(daysFromNow: number, hour = 12): string {
  return new Date(2026, 9, 10 + daysFromNow, hour, 0).toISOString();
}

function connection(overrides: Partial<BankConnection> = {}): BankConnection {
  return {
    id: "0f8c2a54-6a3e-4c1e-9d7b-1b2c3d4e5f60",
    provider: "enable_banking",
    aspspName: "ING",
    aspspCountry: "NL",
    label: "Joint account",
    status: "active",
    consentExpiresAt: at(30),
    lastSyncedAt: null,
    ingestionEnabled: true,
    syncFrequency: "twice_daily",
    accounts: [],
    lastSyncStatus: null,
    lastSyncError: null,
    canManage: true,
    ...overrides,
  };
}

describe("consent urgency", () => {
  it("starts warning exactly seven calendar days out", () => {
    expect(consentUrgency(connection({ consentExpiresAt: at(8) }), NOW)).toEqual({ kind: "ok" });
    expect(consentUrgency(connection({ consentExpiresAt: at(7) }), NOW)).toEqual({ kind: "expiring", days: 7 });
  });

  it("counts calendar days, so a consent ending early tomorrow is still one day away", () => {
    expect(consentUrgency(connection({ consentExpiresAt: at(1, 1) }), NOW)).toEqual({ kind: "expiring", days: 1 });
    expect(consentUrgency(connection({ consentExpiresAt: at(0, 18) }), NOW)).toEqual({ kind: "expiring", days: 0 });
  });

  it("escalates once the stored date has passed", () => {
    expect(consentUrgency(connection({ consentExpiresAt: at(-1) }), NOW)).toEqual({ kind: "expired" });
  });

  it("treats a connection the sync marked expired as expired whatever its stored date", () => {
    expect(consentUrgency(connection({ status: "expired", consentExpiresAt: at(60) }), NOW)).toEqual({ kind: "expired" });
  });

  it("leaves the credential states to their own hint", () => {
    expect(consentUrgency(connection({ status: "credentials_mismatch", consentExpiresAt: at(1) }), NOW)).toEqual({ kind: "ok" });
  });
});

describe("dismissal keys", () => {
  it("change when a re-link moves the expiry date", () => {
    const before = consentNoticeKey(connection({ consentExpiresAt: "2026-10-12T14:45:11.215835Z" }));
    const after = consentNoticeKey(connection({ consentExpiresAt: "2027-01-10T14:45:11Z" }));

    expect(before).toBe("0f8c2a54-6a3e-4c1e-9d7b-1b2c3d4e5f60@2026-10-12");
    expect(after).not.toBe(before);
  });

  it("persist only the connections still in the window", () => {
    const due = connection({ id: "a", consentExpiresAt: at(2) });
    const later = connection({ id: "b", consentExpiresAt: at(40) });

    expect(dismissedAfterHiding(consentAlerts([due, later], NOW))).toEqual([consentNoticeKey(due)]);
  });
});
