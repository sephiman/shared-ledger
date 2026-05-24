import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { apiClient, seedCsrf } from "@/api/client";

export interface HouseholdMembership {
  householdId: string;
  name: string;
  currency: string;
  role: "owner" | "member";
}

export interface Me {
  id: string;
  email: string;
  locale: "en" | "es";
  households: HouseholdMembership[];
}

interface AuthContextValue {
  user: Me | null;
  loading: boolean;
  activeHouseholdId: string | null;
  setActiveHouseholdId: (id: string) => void;
  login: (email: string, password: string, rememberMe?: boolean) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
  setUser: (m: Me) => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

const HOUSEHOLD_KEY = "sl.activeHousehold";

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);
  const [activeHouseholdId, _setActive] = useState<string | null>(() => localStorage.getItem(HOUSEHOLD_KEY));

  const setActiveHouseholdId = useCallback((id: string) => {
    localStorage.setItem(HOUSEHOLD_KEY, id);
    _setActive(id);
  }, []);

  const refresh = useCallback(async () => {
    try {
      const me = await apiClient.get<Me>("/auth/me");
      setUser(me.data);
      if (!activeHouseholdId && me.data.households.length > 0) {
        setActiveHouseholdId(me.data.households[0].householdId);
      }
    } catch {
      setUser(null);
    }
  }, [activeHouseholdId, setActiveHouseholdId]);

  useEffect(() => {
    void (async () => {
      await seedCsrf();
      await refresh();
      setLoading(false);
    })();
  }, [refresh]);

  const login = useCallback(
    async (email: string, password: string, rememberMe = true) => {
      await seedCsrf();
      const res = await apiClient.post<Me>("/auth/login", { email, password, rememberMe });
      setUser(res.data);
      if (res.data.households.length > 0) {
        setActiveHouseholdId(res.data.households[0].householdId);
      }
    },
    [setActiveHouseholdId],
  );

  const logout = useCallback(async () => {
    await apiClient.post("/auth/logout");
    setUser(null);
    localStorage.removeItem(HOUSEHOLD_KEY);
    _setActive(null);
    await seedCsrf();
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, loading, activeHouseholdId, setActiveHouseholdId, login, logout, refresh, setUser }),
    [user, loading, activeHouseholdId, setActiveHouseholdId, login, logout, refresh],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}

export function useActiveHousehold(): HouseholdMembership {
  const { user, activeHouseholdId } = useAuth();
  const found = user?.households.find((h) => h.householdId === activeHouseholdId);
  if (!found) throw new Error("No active household");
  return found;
}
