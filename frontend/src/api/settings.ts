import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
import { useAuth, type Me } from "@/auth/AuthContext";

export interface Household {
  id: string;
  name: string;
  currency: string;
  defaultLocale: string;
  role: string;
}

export interface Invitation {
  id: string;
  role: "owner" | "member";
  email: string | null;
  createdAt: string;
  expiresAt: string;
  accepted: boolean;
}

export interface IssuedInvitation {
  id: string;
  token: string;
  role: "owner" | "member";
  email: string | null;
  expiresAt: string;
}

export interface HouseholdMemberRow {
  userId: string;
  email: string;
  role: "owner" | "member";
  joinedAt: string;
}

export function useHousehold(householdId: string) {
  return useQuery({
    queryKey: ["household", householdId],
    queryFn: async () => (await apiClient.get<Household>(`/households/${householdId}`)).data,
  });
}

export function useUpdateHousehold(householdId: string) {
  const qc = useQueryClient();
  const { refresh } = useAuth();
  return useMutation({
    mutationFn: async (patch: Partial<Household>) =>
      (await apiClient.patch<Household>(`/households/${householdId}`, patch)).data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["household", householdId] });
      // Pages read currency/name from useActiveHousehold() (sourced from /auth/me);
      // refresh so changes propagate beyond the Settings card.
      void refresh();
    },
  });
}

export function useHouseholdMembers(householdId: string) {
  return useQuery({
    queryKey: ["household-members", householdId],
    queryFn: async () =>
      (await apiClient.get<HouseholdMemberRow[]>(`/households/${householdId}/members`)).data,
  });
}

export function useInvitations(householdId: string) {
  return useQuery({
    queryKey: ["invitations", householdId],
    queryFn: async () => (await apiClient.get<Invitation[]>(`/households/${householdId}/invitations`)).data,
  });
}

export function useIssueInvitation(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { email?: string; role: "owner" | "member" }) =>
      (await apiClient.post<IssuedInvitation>(`/households/${householdId}/invitations`, input)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["invitations", householdId] }),
  });
}

export function useRevokeInvitation(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/households/${householdId}/invitations/${id}`);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["invitations", householdId] }),
  });
}

export function useChangePassword() {
  return useMutation({
    mutationFn: async (input: { currentPassword: string; newPassword: string }) => {
      await apiClient.post("/auth/password", input);
    },
  });
}

export function useCreateHousehold() {
  return useMutation({
    mutationFn: async (input: { name: string; currency: string; defaultLocale: "en" | "es" }) =>
      (await apiClient.post<Household>("/households", input)).data,
  });
}

export function useSetDefaultHousehold() {
  return useMutation({
    mutationFn: async (householdId: string) =>
      (await apiClient.put<Me>("/auth/me/default-household", { householdId })).data,
  });
}

export function useDeleteHousehold() {
  return useMutation({
    mutationFn: async (householdId: string) => {
      await apiClient.delete(`/households/${householdId}`);
    },
  });
}

export function useWipeHouseholdData(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (confirmation: string) => {
      await apiClient.post(`/households/${householdId}/data-wipe`, { confirmation });
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["transactions", householdId] });
      void qc.invalidateQueries({ queryKey: ["quick-chips", householdId] });
      void qc.invalidateQueries({ queryKey: ["snapshots", householdId] });
      void qc.invalidateQueries({ queryKey: ["snapshot-prefill", householdId] });
      void qc.invalidateQueries({ queryKey: ["movements", householdId] });
      void qc.invalidateQueries({ queryKey: ["analytics", householdId] });
      void qc.invalidateQueries({ queryKey: ["fire-projection", householdId] });
      void qc.invalidateQueries({ queryKey: ["budgets", householdId] });
      void qc.invalidateQueries({ queryKey: ["budgets-month-summary", householdId] });
    },
  });
}
