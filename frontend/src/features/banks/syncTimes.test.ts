import { describe, expect, it } from "vitest";
import { convertWallTime, oppositeTime } from "./syncTimes";

describe("convertWallTime", () => {
  const winter = new Date("2026-11-04T12:00:00Z");
  const summer = new Date("2026-07-01T12:00:00Z");

  it("keeps the time when both zones are the same", () => {
    expect(convertWallTime("07:00", "UTC", "UTC", winter)).toBe("07:00");
  });

  it("shows the UTC default in a viewer's winter and summer offsets", () => {
    expect(convertWallTime("07:00", "UTC", "Europe/Madrid", winter)).toBe("08:00");
    expect(convertWallTime("19:00", "UTC", "Europe/Madrid", summer)).toBe("21:00");
  });

  it("converts towards UTC and across midnight", () => {
    expect(convertWallTime("00:30", "Europe/Madrid", "UTC", winter)).toBe("23:30");
  });

  it("handles a quarter-hour offset", () => {
    expect(convertWallTime("06:00", "Asia/Kathmandu", "UTC", winter)).toBe("00:15");
  });
});

describe("oppositeTime", () => {
  it("suggests twelve hours later, wrapping past midnight", () => {
    expect(oppositeTime("07:00")).toBe("19:00");
    expect(oppositeTime("21:45")).toBe("09:45");
  });
});
