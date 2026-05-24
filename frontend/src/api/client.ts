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
}

export function asApiError(err: unknown): ApiError {
  const ax = err as AxiosError<ApiError>;
  if (ax?.response?.data?.code) return ax.response.data;
  return { code: "UNKNOWN", message: ax?.message ?? "Unknown error" };
}

export async function seedCsrf(): Promise<void> {
  await apiClient.get("/auth/csrf");
}
