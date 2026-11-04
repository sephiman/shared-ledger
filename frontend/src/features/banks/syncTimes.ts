const MINUTE_MS = 60_000;

/** The viewer's IANA zone, e.g. "Europe/Madrid"; what a saved schedule is anchored to. */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

function wallClockParts(instant: Date, zone: string): Record<string, number> {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: zone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).formatToParts(instant);
  return Object.fromEntries(parts.filter((p) => p.type !== "literal").map((p) => [p.type, Number(p.value)]));
}

function offsetMinutes(instant: Date, zone: string): number {
  const p = wallClockParts(instant, zone);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute);
  return Math.round((asUtc - Math.floor(instant.getTime() / MINUTE_MS) * MINUTE_MS) / MINUTE_MS);
}

/** "HH:mm" in [fromZone] on [day]'s calendar date, re-expressed as "HH:mm" in [toZone]. */
export function convertWallTime(time: string, fromZone: string, toZone: string, day: Date = new Date()): string {
  if (fromZone === toZone) return time;
  const [hour, minute] = time.split(":").map(Number);
  const guess = Date.UTC(day.getUTCFullYear(), day.getUTCMonth(), day.getUTCDate(), hour, minute);
  // Second pass corrects the guess when the first offset was taken across a DST change.
  let instant = guess - offsetMinutes(new Date(guess), fromZone) * MINUTE_MS;
  instant = guess - offsetMinutes(new Date(instant), fromZone) * MINUTE_MS;
  const p = wallClockParts(new Date(instant), toZone);
  return `${String(p.hour).padStart(2, "0")}:${String(p.minute).padStart(2, "0")}`;
}

/** Twelve hours after [time]: a sensible first guess for the second daily sync. */
export function oppositeTime(time: string): string {
  const [hour, minute] = time.split(":").map(Number);
  return `${String((hour + 12) % 24).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}
