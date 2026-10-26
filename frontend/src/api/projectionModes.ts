import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type ProjectionMode = "ENVELOPE" | "PRORATE";

export interface GroupProjectionMode {
  groupCode: string;
  mode: ProjectionMode;
}

export function useProjectionModes(householdId: string) {
  return useQuery({
    queryKey: ["projection-modes", householdId],
    queryFn: async () => (await apiClient.get<GroupProjectionMode[]>(`/households/${householdId}/projection-modes`)).data,
  });
}

export function useSetProjectionMode(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    // The row confirms or reports inline, next to the control, instead of the global toast.
    meta: { silentSuccess: true, silentError: true },
    mutationFn: async ({ groupCode, mode }: GroupProjectionMode) =>
      (await apiClient.put<GroupProjectionMode>(`/households/${householdId}/projection-modes/${groupCode}`, { mode })).data,
    onSuccess: (saved) => {
      qc.setQueryData<GroupProjectionMode[]>(["projection-modes", householdId], (modes) =>
        modes?.map((m) => (m.groupCode === saved.groupCode ? saved : m)),
      );
      void qc.invalidateQueries({ queryKey: ["analytics", householdId, "money-flow-projection"] });
    },
  });
}
