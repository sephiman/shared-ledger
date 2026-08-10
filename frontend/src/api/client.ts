import axios, { type AxiosError } from "axios";

function readCookie(name: string): string | null {
  return (
    document.cookie
      .split("; ")
      .map((c) => c.split("="))
      .find(([k]) => k === name)?.[1] ?? null
  );
}

export const apiClient = axios.create({
  baseURL: "/api",
  withCredentials: true,
  headers: { "Content-Type": "application/json" },
});

apiClient.interceptors.request.use((config) => {
  const token = readCookie("XSRF-TOKEN");
  if (token && config.method && ["post", "put", "patch", "delete"].includes(config.method.toLowerCase())) {
    config.headers.set("X-XSRF-TOKEN", decodeURIComponent(token));
  }
  return config;
});

export interface ApiError {
  code: string;
  message: string;
  fields?: Record<string, string>;
  /** Positional arguments the server interpolated into `message`, exposed so our own
   *  `errors.<code>` translation can name them as `{{0}}`, `{{1}}` … instead of staying vague. */
  args?: string[];
}

export function asApiError(err: unknown): ApiError {
  const ax = err as AxiosError<ApiError>;
  if (ax?.response?.data?.code) return ax.response.data;
  return { code: "UNKNOWN", message: ax?.message ?? "Unknown error" };
}

/** Localized message for a failed request: the `errors.<code>` translation, falling back to the server
 *  message. The server's positional args are passed through as `{{0}}`, `{{1}}` …, so our translation can
 *  name the offending value — the server's own text is not usable for that, since its locale comes from the
 *  browser's Accept-Language rather than the language chosen in the app. */
export function apiErrorMessage(
  err: unknown,
  t: (key: string, options: Record<string, unknown>) => string,
): string {
  const api = asApiError(err);
  const args = Object.fromEntries((api.args ?? []).map((value, i) => [i, value]));
  return t(`errors.${api.code}`, { defaultValue: api.message, ...args });
}

export async function seedCsrf(): Promise<void> {
  await apiClient.get("/auth/csrf");
}
