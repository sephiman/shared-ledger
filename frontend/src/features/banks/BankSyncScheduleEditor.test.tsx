import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { AxiosResponse } from "axios";
import { apiClient } from "@/api/client";
import type { BankSyncSchedule, BankSyncScheduleInput } from "@/api/banks";
import i18n from "@/i18n";
import { BankSyncScheduleEditor } from "./BankSyncScheduleEditor";

const URL = "/households/h1/banks/sync-schedule";
const ok = <T,>(data: T) => Promise.resolve({ data } as AxiosResponse<T>);

let stored: BankSyncSchedule;
const saved: BankSyncScheduleInput[] = [];

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

beforeEach(() => {
  saved.length = 0;
  stored = { times: ["07:00", "19:00"], timezone: "UTC", isDefault: true };
  // Pin the viewer's zone so the stored UTC times show unconverted on any machine.
  const resolved = Intl.DateTimeFormat().resolvedOptions();
  vi.spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions").mockReturnValue({ ...resolved, timeZone: "UTC" });
  vi.spyOn(apiClient, "get").mockImplementation((url: string) => {
    if (url === URL) return ok(stored);
    throw new Error(`unexpected GET ${url}`);
  });
  vi.spyOn(apiClient, "put").mockImplementation((url: string, body: unknown) => {
    const input = body as BankSyncScheduleInput;
    saved.push(input);
    stored = { ...input, isDefault: false };
    return ok(stored);
  });
  vi.spyOn(apiClient, "delete").mockImplementation(() => {
    stored = { times: ["07:00", "19:00"], timezone: "UTC", isDefault: true };
    return ok(stored);
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function renderEditor() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <BankSyncScheduleEditor householdId="h1" />
    </QueryClientProvider>,
  );
}

describe("BankSyncScheduleEditor", () => {
  it("shows the default times and marks them as the default", async () => {
    renderEditor();

    expect(await screen.findByLabelText("Time 1")).toHaveValue("07:00");
    expect(screen.getByLabelText("Time 2")).toHaveValue("19:00");
    expect(screen.getByText("Default")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reset to default" })).not.toBeInTheDocument();
  });

  it("saves a single time in the viewer's zone", async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByLabelText("Time 2");

    await user.click(screen.getByRole("button", { name: "Remove second time" }));
    await user.clear(screen.getByLabelText("Time 1"));
    await user.type(screen.getByLabelText("Time 1"), "06:30");
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(saved).toEqual([{ times: ["06:30"], timezone: "UTC" }]));
    expect(await screen.findByRole("button", { name: "Reset to default" })).toBeInTheDocument();
  });

  it("suggests a second time twelve hours after the first", async () => {
    stored = { times: ["08:15"], timezone: "UTC", isDefault: false };
    const user = userEvent.setup();
    renderEditor();
    await screen.findByLabelText("Time 1");

    await user.click(screen.getByRole("button", { name: "Add a second time" }));

    expect(screen.getByLabelText("Time 2")).toHaveValue("20:15");
  });

  it("surfaces the server's reason when a schedule is rejected", async () => {
    vi.spyOn(apiClient, "put").mockRejectedValue({
      isAxiosError: true,
      response: { status: 400, data: { code: "BANK_SYNC_TIMES_TOO_CLOSE", message: "too close", args: [2] } },
    });
    const user = userEvent.setup();
    renderEditor();
    await screen.findByLabelText("Time 2");

    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText("The two sync times must be at least 2 hours apart")).toBeInTheDocument();
  });
});
