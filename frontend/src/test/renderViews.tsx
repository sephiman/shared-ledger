import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ExplorerTab } from "@/features/analytics/ExplorerTab";
import { TransactionsPage } from "@/features/transactions/TransactionsPage";
import { LocationProbe } from "./LocationProbe";

/** Both views the question box can lead to, under one router, starting at [url]. */
export function renderViews(url: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/analytics/explorer" element={<ExplorerTab />} />
          <Route path="/transactions" element={<TransactionsPage />} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
