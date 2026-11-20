import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LiabilitiesTab } from "@/features/networth/LiabilitiesTab";
import type { Liability, ValueEntry } from "@/api/networth";
import i18n from "@/i18n";

const upsertMock = vi.fn().mockResolvedValue(undefined);
const deleteMock = vi.fn();
let liabilitiesData: Liability[] = [];
let valuesByLiability: Record<string, ValueEntry[]> = {};

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", name: "Home", currency: "EUR", role: "owner" }),
}));

vi.mock("@/api/networth", () => ({
  useLiabilities: () => ({ data: liabilitiesData }),
  useUpsertLiability: () => ({ mutateAsync: upsertMock, isPending: false }),
  useDeleteLiability: () => ({ mutate: deleteMock, isPending: false }),
  useLiabilityValues: (_hid: string, id: string) => ({ data: valuesByLiability[id] }),
  useAddLiabilityValue: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useUpdateLiabilityValue: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useDeleteLiabilityValue: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("es");
  Element.prototype.scrollIntoView = vi.fn();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  liabilitiesData = [];
  valuesByLiability = {};
});

describe("LiabilitiesTab", () => {
  it("shows the latest recorded value and date in the header and only shows 'Sin valor aún' when history is empty", () => {
    liabilitiesData = [
      {
        id: "l-ele",
        name: "Credito Ele",
        type: "other_debt",
        active: true,
        amortizable: false,
        latestBalance: "1000.00",
        latestBalanceDate: "2026-11-20",
        countsTowardNetWorth: true,
      } as Liability,
      {
        id: "l-empty",
        name: "Sin historial",
        type: "other_debt",
        active: true,
        amortizable: false,
        latestBalance: null,
        latestBalanceDate: null,
        countsTowardNetWorth: true,
      } as Liability,
    ];
    valuesByLiability = {
      "l-ele": [{ id: "v1", date: "2026-11-20", value: "1000.00" }],
      "l-empty": [],
    };

    render(<LiabilitiesTab />);

    const eleCard = screen.getByText("Credito Ele").closest("li")!;
    expect(within(eleCard).queryByText("Sin valor aún")).not.toBeInTheDocument();
    expect(within(eleCard).getAllByText(/1[.,]?000,00\s*€/).length).toBeGreaterThanOrEqual(2);
    expect(within(eleCard).getAllByText(/20\s+nov\s+2026/i).length).toBeGreaterThanOrEqual(2);

    const emptyCard = screen.getByText("Sin historial").closest("li")!;
    expect(within(emptyCard).getByText("Sin valor aún")).toBeInTheDocument();
  });

  it("shows the type badge, the counts-toward-net-worth badge and line on cards, and keeps CON PRÉSTAMOS on amortizable cards", () => {
    liabilitiesData = [
      {
        id: "l-mort",
        name: "Casa",
        type: "mortgage",
        active: true,
        amortizable: true,
        chargeDay: 1,
        computedBalance: "150000.00",
        computedInstalment: "800.00",
        countsTowardNetWorth: true,
      } as Liability,
      {
        id: "l-counted",
        name: "Tarjeta",
        type: "other_debt",
        active: true,
        amortizable: false,
        latestBalance: "500.00",
        latestBalanceDate: "2026-11-20",
        countsTowardNetWorth: true,
      } as Liability,
      {
        id: "l-excluded",
        name: "Credito Ele",
        type: "other_debt",
        active: true,
        amortizable: false,
        latestBalance: "1000.00",
        latestBalanceDate: "2026-11-20",
        countsTowardNetWorth: false,
      } as Liability,
    ];
    valuesByLiability = {
      "l-counted": [{ id: "v1", date: "2026-11-20", value: "500.00" }],
      "l-excluded": [{ id: "v2", date: "2026-11-20", value: "1000.00" }],
    };

    render(<LiabilitiesTab />);

    const mortCard = screen.getByText("Casa").closest("li")!;
    expect(within(mortCard).getByText("Hipoteca")).toBeInTheDocument();
    expect(within(mortCard).getByText("Con préstamos")).toBeInTheDocument();

    const countedCard = screen.getByText("Tarjeta").closest("li")!;
    expect(within(countedCard).getByText("Deuda normal")).toBeInTheDocument();
    expect(within(countedCard).getByText("Resta del patrimonio neto")).toBeInTheDocument();

    const excludedCard = screen.getByText("Credito Ele").closest("li")!;
    expect(within(excludedCard).getByText("Deuda normal")).toBeInTheDocument();
    expect(within(excludedCard).getByText("No cuenta para el patrimonio neto")).toBeInTheDocument();
    expect(within(excludedCard).getByText("No cuenta")).toBeInTheDocument();
  });

  it("renders create form in the required order, requires positive amount only when loans are off, and hides amount when loans are on", async () => {
    const user = userEvent.setup();
    render(<LiabilitiesTab />);

    await user.click(screen.getByRole("button", { name: "Nuevo pasivo" }));

    const nameInput = screen.getByLabelText("Nombre");
    const typeSelect = screen.getByLabelText("Tipo");
    const amortCheckbox = screen.getByRole("checkbox", { name: "Contiene préstamos" });
    const amountInput = screen.getByLabelText("Deuda pendiente");
    const dateInput = screen.getByLabelText("A fecha de");
    const countsCheckbox = screen.getByRole("checkbox", { name: "Cuenta para el patrimonio neto" });
    const activeCheckbox = screen.getByRole("checkbox", { name: "Activo" });

    // Order: Name -> Type -> Contiene préstamos -> Deuda pendiente -> A fecha de -> Cuenta para el patrimonio neto -> Activo.
    const ordered = [nameInput, typeSelect, amortCheckbox, amountInput, dateInput, countsCheckbox, activeCheckbox];
    for (let i = 0; i < ordered.length - 1; i++) {
      expect(ordered[i].compareDocumentPosition(ordered[i + 1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    }

    // Defaults: type = other_debt, counts = true, active = true, amortizable = false.
    expect(typeSelect).toHaveValue("other_debt");
    expect(countsCheckbox).toBeChecked();
    expect(activeCheckbox).toBeChecked();
    expect(amortCheckbox).not.toBeChecked();

    // Missing/non-positive amount blocks submit when loans are off.
    await user.type(nameInput, "Credito Ele");
    await user.click(screen.getByRole("button", { name: "Guardar" }));
    expect(upsertMock).not.toHaveBeenCalled();

    // Enter valid amount with comma decimal and save.
    await user.type(amountInput, "1000,50");
    await user.click(screen.getByRole("button", { name: "Guardar" }));
    expect(upsertMock).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "Credito Ele",
        type: "other_debt",
        amortizable: false,
        countsTowardNetWorth: true,
        active: true,
        initialBalance: "1000.50",
        initialBalanceDate: expect.any(String),
      }),
    );

    // Open create again, turn Contiene préstamos on: amount & date disappear, loans note appears, no amount needed.
    upsertMock.mockClear();
    await user.click(screen.getByRole("button", { name: "Nuevo pasivo" }));
    await user.type(screen.getByLabelText("Nombre"), "Hipoteca");
    await user.selectOptions(screen.getByLabelText("Tipo"), "mortgage");
    await user.click(screen.getByRole("checkbox", { name: "Contiene préstamos" }));

    expect(screen.queryByLabelText("Deuda pendiente")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("A fecha de")).not.toBeInTheDocument();
    expect(screen.getByText(/préstamos que añadas/i)).toBeInTheDocument();

    await user.type(screen.getByLabelText("Día de cargo"), "5");
    await user.click(screen.getByRole("button", { name: "Guardar" }));
    expect(upsertMock).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "Hipoteca",
        type: "mortgage",
        amortizable: true,
        chargeDay: 5,
        initialBalance: null,
        initialBalanceDate: null,
      }),
    );
  });

  it("shows the edit form with type and counts-toward-net-worth (and note), without initial amount inputs", async () => {
    liabilitiesData = [
      {
        id: "l-excluded",
        name: "Credito Ele",
        type: "other_debt",
        active: true,
        amortizable: false,
        latestBalance: "1000.00",
        latestBalanceDate: "2026-11-20",
        countsTowardNetWorth: false,
      } as Liability,
    ];
    valuesByLiability = {
      "l-excluded": [{ id: "v2", date: "2026-11-20", value: "1000.00" }],
    };

    const user = userEvent.setup();
    render(<LiabilitiesTab />);

    const excludedCard = screen.getByText("Credito Ele").closest("li")!;
    await user.click(within(excludedCard).getByTitle("Editar detalles"));

    expect(within(excludedCard).queryByLabelText("Deuda pendiente")).not.toBeInTheDocument();
    expect(within(excludedCard).getByLabelText("Tipo")).toHaveValue("other_debt");
    const editCheckbox = within(excludedCard).getByRole("checkbox", { name: "Cuenta para el patrimonio neto" });
    expect(editCheckbox).not.toBeChecked();
    expect(within(excludedCard).getByText(/instantáneas ya guardadas no cambian/i)).toBeInTheDocument();

    await user.selectOptions(within(excludedCard).getByLabelText("Tipo"), "mortgage");
    await user.click(editCheckbox);
    await user.click(within(excludedCard).getByRole("button", { name: "Guardar" }));

    expect(upsertMock).toHaveBeenCalledWith(
      expect.objectContaining({
        id: "l-excluded",
        type: "mortgage",
        countsTowardNetWorth: true,
      }),
    );
  });
});
