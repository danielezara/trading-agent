import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AppRoutes } from "./App";

const overview = {
  data: {
    operational_state: "OBSERVATION",
    trading_enabled: false,
    equity: null,
    broker_cash: null,
    assigned_budget: null,
    available_cash: null,
    reserved_cash: null,
    cash_buffer: null,
    ledger_status: "UNOBSERVED",
    configuration_version_id: "11111111-1111-1111-1111-111111111111",
    configuration_version_number: 1,
  },
  meta: { environment: "DEMO", base_currency: null },
  warnings: [],
};

const configuration = {
  data: {
    version_id: "11111111-1111-1111-1111-111111111111",
    version_number: 1,
    author: "system",
    created_at: "2026-11-27T00:00:00Z",
    reason: "Parametri economici non impostati.",
    content_sha256: "a".repeat(64),
    active: true,
    budget: {
      base_currency: null,
      assigned_budget_minor: null,
      cash_buffer_minor: null,
      max_positions: null,
    },
  },
  meta: { environment: "DEMO", base_currency: null },
  warnings: [],
};

const operational = {
  data: {
    state: "OBSERVATION",
    since: "2026-11-27T00:00:00Z",
    reason: "Avvio in OBSERVATION.",
    generation: 1,
    actions: {
      new_entries: false,
      local_exits: false,
      cancel: false,
      reconcile_permitted: true,
      reconcile_wired: false,
    },
  },
  meta: { environment: "DEMO", base_currency: null },
  warnings: [],
};

function jsonFor(path: string) {
  if (path.includes("overview")) return overview;
  if (path.includes("configurations")) return configuration;
  return operational;
}

function renderAt(path: string) {
  vi.stubGlobal("fetch", (input: string) => Promise.resolve({
    ok: true,
    status: 200,
    json: async () => jsonFor(String(input)),
  }));
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  );
}

describe("dashboard skeleton", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows the demo banner and observation state from the API", async () => {
    renderAt("/");
    expect(screen.getByText("DEMO")).toBeInTheDocument();
    expect(await screen.findByTestId("operational-state")).toHaveTextContent("OBSERVATION");
    expect(screen.getByRole("heading", { name: "Panoramica" })).toBeInTheDocument();
    expect(screen.getAllByText("non disponibile").length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: /compra|vendi|buy|sell/i })).not.toBeInTheDocument();
  });

  it("shows the active configuration version", async () => {
    renderAt("/configurazione");
    expect(await screen.findByText("system")).toBeInTheDocument();
    expect(screen.getByText("Parametri economici non impostati.")).toBeInTheDocument();
  });

  it("keeps chat read only", async () => {
    renderAt("/chat");
    expect(await screen.findByTestId("operational-state")).toHaveTextContent("OBSERVATION");
    const input = screen.getByLabelText("Messaggio");
    expect(input).toBeDisabled();
    expect(screen.getByText(/non crea ordini/i)).toBeInTheDocument();
  });

  it("has no order action on the portfolio route", async () => {
    renderAt("/portafoglio");
    expect(screen.getByRole("heading", { name: "Portafoglio" })).toBeInTheDocument();
    expect(await screen.findByText(/Stato ledger: UNOBSERVED/)).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
