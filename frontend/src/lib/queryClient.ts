import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";
import i18n from "@/i18n";
import { asApiError } from "@/api/client";
import { showToast } from "@/lib/toastBus";

interface MutationMeta {
  silentSuccess?: boolean;
  silentError?: boolean;
  successMessage?: string;
}

/** The app's query client: every mutation announces success or failure unless its meta says otherwise. */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        refetchOnWindowFocus: false,
        staleTime: 30_000,
        retry: 1,
      },
    },
    queryCache: new QueryCache({
      onError: (error, query) => {
        if ((query.meta as { silentError?: boolean } | undefined)?.silentError) return;
        // Queries have no local error UI in most views — surface failures like mutations do.
        const api = asApiError(error);
        showToast(i18n.t(`errors.${api.code}`, api.message), "error");
      },
    }),
    mutationCache: new MutationCache({
      onSuccess: (_data, _vars, _ctx, mutation) => {
        const meta = mutation.options.meta as MutationMeta | undefined;
        if (meta?.silentSuccess) return;
        const key = meta?.successMessage ?? "common.saved";
        showToast(i18n.t(key), "success");
      },
      onError: (error, _vars, _ctx, mutation) => {
        const meta = mutation.options.meta as MutationMeta | undefined;
        if (meta?.silentError) return;
        // Never let a mutation fail silently — surface the server's error code/message.
        const api = asApiError(error);
        showToast(i18n.t(`errors.${api.code}`, api.message), "error");
      },
    }),
  });
}
