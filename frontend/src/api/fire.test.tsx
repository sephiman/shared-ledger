import type { ReactNode } from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "@/api/client";
import { createQueryClient } from "@/lib/queryClient";
import { subscribeToasts, type Toast } from "@/lib/toastBus";
import { useFirePreview } from "./fire";

describe("useFirePreview", () => {
  afterEach(() => vi.restoreAllMocks());

  it("does not announce 'Saved' for a what-if, which saves nothing", async () => {
    vi.spyOn(apiClient, "post").mockResolvedValue({ data: {} });
    const seen: Toast[][] = [];
    const unsubscribe = subscribeToasts((toasts) => seen.push(toasts));
    const client = createQueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;

    const { result } = renderHook(() => useFirePreview("h1"), { wrapper });
    act(() => result.current.mutate({ lumpSum: "10000" }));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    unsubscribe();
    expect(seen.flat().filter((t) => t.kind === "success")).toEqual([]);
  });
});
