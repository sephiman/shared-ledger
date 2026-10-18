import { useCallback, useState } from "react";

export const FORECAST_WINDOWS = [3, 6, 12] as const;
export type ForecastWindow = (typeof FORECAST_WINDOWS)[number];

const DEFAULT_WINDOW: ForecastWindow = 3;

/** One key for the Forecast and the month projection: two windows would give two estimates per category. */
const STORAGE_KEY = "sl.analytics.forecastWindow";

export function parseForecastWindow(raw: string | null): ForecastWindow {
  const n = Number(raw);
  return FORECAST_WINDOWS.find((w) => w === n) ?? DEFAULT_WINDOW;
}

/** Per browser, like the theme: the moving-average window shared by every view that estimates a month. */
export function useForecastWindow(): [ForecastWindow, (next: ForecastWindow) => void] {
  const [value, setValue] = useState<ForecastWindow>(() => {
    try {
      return parseForecastWindow(localStorage.getItem(STORAGE_KEY));
    } catch {
      return DEFAULT_WINDOW;
    }
  });

  const update = useCallback((next: ForecastWindow) => {
    setValue(next);
    try {
      localStorage.setItem(STORAGE_KEY, String(next));
    } catch {
      // A browser that refuses storage still gets the selector; it just won't remember it.
    }
  }, []);

  return [value, update];
}
