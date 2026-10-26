import { useQuery } from "@tanstack/react-query";
import { apiClient } from "./client";

/** Optional integrations this instance has, for signed-in users. False means the feature does not exist here:
 *  nothing related to it is rendered or requested. */
export interface InstanceFeatures {
  aiCategorySuggestions: boolean;
}

export function useInstanceFeatures() {
  return useQuery({
    queryKey: ["instance-features"],
    queryFn: async () => (await apiClient.get<InstanceFeatures>("/features")).data,
    staleTime: Infinity,
    meta: { silentError: true },
  });
}
