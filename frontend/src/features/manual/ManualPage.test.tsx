import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import i18n from "@/i18n";
import { ManualPage } from "./ManualPage";
import { MANUAL_CHAPTERS_EN } from "./content.en";
import { MANUAL_CHAPTERS_ES } from "./content.es";

function renderManual() {
  return render(
    <MemoryRouter initialEntries={["/manual"]}>
      <ManualPage />
    </MemoryRouter>,
  );
}

afterEach(async () => {
  cleanup();
  await i18n.changeLanguage("en");
});

describe("ManualPage", () => {
  it("renders the English manual by default and switches to Spanish when the locale changes", async () => {
    renderManual();

    expect(screen.getByRole("heading", { level: 1, name: "User manual" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Transactions, categories & refunds" })).toBeInTheDocument();

    await i18n.changeLanguage("es");

    expect(screen.getByRole("heading", { level: 1, name: "Manual de usuario" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Transacciones, categorías y devoluciones" })).toBeInTheDocument();
  });

  it("filters sections accent-insensitively and restores all sections when cleared", async () => {
    await i18n.changeLanguage("es");
    const user = userEvent.setup();
    renderManual();

    const search = screen.getByRole("searchbox", { name: "Buscar en el manual" });
    await user.type(search, "amortizacion");

    expect(screen.getByRole("heading", { level: 3, name: "Activos, Pasivos y Prestado" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 3, name: "Hogares y roles" })).not.toBeInTheDocument();

    await user.clear(search);
    await user.type(search, "xyznonexistent");
    expect(screen.getByText(/Ninguna sección del manual coincide/)).toBeInTheDocument();

    await user.click(screen.getAllByRole("button", { name: "Limpiar búsqueda" })[0]);
    expect(screen.getByRole("heading", { level: 3, name: "Hogares y roles" })).toBeInTheDocument();
  });

  it("keeps English and Spanish chapters and section IDs in sync", () => {
    const enStructure = MANUAL_CHAPTERS_EN.map((c) => ({
      id: c.id,
      route: c.route?.to ?? null,
      sections: c.sections.map((s) => s.id),
    }));
    const esStructure = MANUAL_CHAPTERS_ES.map((c) => ({
      id: c.id,
      route: c.route?.to ?? null,
      sections: c.sections.map((s) => s.id),
    }));

    expect(esStructure).toEqual(enStructure);
  });
});
