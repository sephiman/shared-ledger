import { useQuery } from "@tanstack/react-query";
import { apiClient, seedCsrf } from "./client";

/** What the login page may offer before anyone is signed in. Optional integrations report `false` until the
 *  operator configures them, and the page shows nothing for them. */
export interface AuthFeatures {
  passwordReset: boolean;
}

export function useAuthFeatures() {
  return useQuery({
    queryKey: ["auth-features"],
    queryFn: async () => (await apiClient.get<AuthFeatures>("/auth/features")).data,
    staleTime: Infinity,
    meta: { silentError: true },
  });
}

export async function requestPasswordReset(email: string): Promise<void> {
  await seedCsrf();
  await apiClient.post("/auth/password-reset", { email });
}

export async function validatePasswordResetToken(token: string): Promise<void> {
  await seedCsrf();
  await apiClient.post("/auth/password-reset/validate", { token });
}

export async function confirmPasswordReset(token: string, newPassword: string): Promise<void> {
  await seedCsrf();
  await apiClient.post("/auth/password-reset/confirm", { token, newPassword });
}
