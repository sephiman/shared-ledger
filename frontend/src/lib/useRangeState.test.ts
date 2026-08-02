import { describe, expect, it } from "vitest";
import { parseStoredRange } from "./useRangeState";

describe("parseStoredRange", () => {
  it("restores a well-formed stored range", () => {
    expect(parseStoredRange('{"preset":"custom","from":"2025-03-01","to":"2026-02-28"}')).toEqual({
      preset: "custom",
      from: "2025-03-01",
      to: "2026-02-28",
    });
  });

  it("rejects anything it cannot trust, so a stale entry falls back to the default", () => {
    expect(parseStoredRange(null)).toBeNull();
    expect(parseStoredRange("not json")).toBeNull();
    expect(parseStoredRange('"1y"')).toBeNull();
    expect(parseStoredRange('{"preset":"18m","from":"","to":""}')).toBeNull();
    expect(parseStoredRange('{"preset":"1y","from":null,"to":""}')).toBeNull();
    expect(parseStoredRange('{"from":"","to":""}')).toBeNull();
  });
});
