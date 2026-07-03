import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface TelegramSettings {
  active: boolean;
  notifyTransactions: boolean;
  notifySnapshots: boolean;
  notifyMovements: boolean;
  notifyLoanPayments: boolean;
  notifyHoldings: boolean;
  notifyRecurringTxn: boolean;
  notifyRecurringLoan: boolean;
  chatId: string | null;
  tokenConfigured: boolean;
}

export interface TelegramSettingsUpdate {
  active: boolean;
  notifyTransactions: boolean;
  notifySnapshots: boolean;
  notifyMovements: boolean;
  notifyLoanPayments: boolean;
  notifyHoldings: boolean;
  notifyRecurringTxn: boolean;
  notifyRecurringLoan: boolean;
  chatId: string | null;
  // Omit / leave blank to keep the stored token unchanged.
  botToken?: string | null;
}

export interface TelegramTestResult {
  ok: boolean;
  description: string | null;
}

export function useTelegramSettings(householdId: string, enabled: boolean) {
  return useQuery({
    queryKey: ["telegram-settings", householdId],
    enabled,
    queryFn: async () =>
      (await apiClient.get<TelegramSettings>(`/households/${householdId}/telegram-settings`)).data,
  });
}

export function useUpdateTelegramSettings(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: TelegramSettingsUpdate) =>
      (await apiClient.put<TelegramSettings>(`/households/${householdId}/telegram-settings`, body)).data,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["telegram-settings", householdId] }),
  });
}

export function useTestTelegram(householdId: string) {
  return useMutation({
    mutationFn: async () =>
      (await apiClient.post<TelegramTestResult>(`/households/${householdId}/telegram-settings/test`, {})).data,
  });
}
