import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { AxiosResponse } from "axios";
import type { ReactNode } from "react";
import { apiClient } from "./client";
import { useCreatePart } from "./amortization";
import { useAddLiabilityValue, useDeleteLiability } from "./networth";

const ok = <T,>(data: T) => Promise.resolve({ data } as AxiosResponse<T>);

function makeWrapper(qc: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("liability and loan cache invalidation for Home live wealth", () => {
  it("invalidates named-values when a loan part is added so Home updates without reload", async () => {
    vi.spyOn(apiClient, "post").mockResolvedValue(ok({ id: "p1" }) as unknown as AxiosResponse);
    const qc = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000 } } });
    qc.setQueryData(["named-values", "h1", "2026-11-20"], { assets: {}, liabilities: { l1: "0.00" }, cash: null });

    const { result } = renderHook(() => useCreatePart("h1", "l1"), { wrapper: makeWrapper(qc) });

    await act(async () => {
      await result.current.mutateAsync({
        method: "french",
        startMode: "current_balance",
        originalPrincipal: "100000.00",
        annualRate: "3.00",
        termMonths: 240,
        startDate: "2026-11-20",
      });
    });

    expect(qc.getQueryState(["named-values", "h1", "2026-11-20"])?.isInvalidated).toBe(true);
  });

  it("invalidates named-values when a liability manual value is added or a liability is deleted", async () => {
    vi.spyOn(apiClient, "post").mockResolvedValue(ok({ id: "v1" }) as unknown as AxiosResponse);
    vi.spyOn(apiClient, "delete").mockResolvedValue(ok(undefined) as unknown as AxiosResponse);
    const qc = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000 } } });

    qc.setQueryData(["named-values", "h1", "2026-11-20"], { assets: {}, liabilities: { l1: "1000.00" }, cash: null });
    const addHook = renderHook(() => useAddLiabilityValue("h1", "l1"), { wrapper: makeWrapper(qc) });
    await act(async () => {
      await addHook.result.current.mutateAsync({ date: "2026-11-20", value: "800.00" });
    });
    expect(qc.getQueryState(["named-values", "h1", "2026-11-20"])?.isInvalidated).toBe(true);

    qc.setQueryData(["named-values", "h1", "2026-11-20"], { assets: {}, liabilities: { l1: "800.00" }, cash: null });
    const delHook = renderHook(() => useDeleteLiability("h1"), { wrapper: makeWrapper(qc) });
    await act(async () => {
      await delHook.result.current.mutateAsync("l1");
    });
    expect(qc.getQueryState(["named-values", "h1", "2026-11-20"])?.isInvalidated).toBe(true);
  });
});
