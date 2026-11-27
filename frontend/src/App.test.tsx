import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AppRoutes } from "./App";

const overview = {
  data: {
    operational_state: "OBSERVATION",
    trading_enabled: false,
    equity: null,
    broker_cash: null,
    assigned_budget: { minor_units: 50000, currency: "EUR" },
    available_cash: null,
    reserved_cash: null,
    cash_buffer: null,
    ledger_status: "UNOBSERVED",
    journal_entry_count: 0,
    journal_balanced: true,
    broker_connected: false,
    last_probe_at: null,
    probe_error_class: "KEYS_MISSING",
    account_label: null,
    positions: null,
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
    author: "daniele",
    created_at: "2026-11-27T00:00:00Z",
    reason: "Budget 500 EUR. Perdita massima 10% del budget, poi blocco. Orizzonte in giorni. Allowlist large-cap e ETF, solo long, leva 1. Benchmark MSCI World total return. Capability e licenze non verificate.",
    content_sha256: "a".repeat(64),
    valid_from_bar_open: "2026-11-27T00:00:00Z",
    active: true,
    budget: {
      base_currency: "EUR",
      assigned_budget_minor: 50000,
      cash_buffer_minor: null,
      max_positions: null,
    },
    economic: {
      max_loss_bps: 1000,
      max_loss_action: "block",
      horizon: "days",
      allowlist_universe: "large_cap_stocks_and_etfs",
      allowlist_direction: "long_only",
      leverage: 1,
      benchmark_name: "MSCI World",
      benchmark_return_basis: "total_return",
      technical_calibratable: true,
      source_path: "config/observation-v1.yaml",
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

const decision = {
  data: {
    action: "NO-TRADE",
    reason_codes: ["CAPABILITY_UNVERIFIED", "DATA_MISSING"],
    summary: "NO-TRADE. Motivi: CAPABILITY_UNVERIFIED, DATA_MISSING.",
    input_hash: "b".repeat(64),
    evaluated_at: "2026-11-27T00:00:30Z",
    config_version_id: "11111111-1111-1111-1111-111111111111",
    config_content_sha256: "a".repeat(64),
    audit_sequence: 1,
    audit_event_hash: "c".repeat(64),
    policy: { accepted: false, reason: "MISSING_INTENT", broker_called: false },
    signal: {
      signal_id: "e".repeat(64),
      origin: "strategy-engine",
      direction: "NONE",
      instrument_id: null,
      confidence_millis: 0,
      orderable: false,
      rationale: "NO-TRADE",
    },
  },
  meta: { environment: "DEMO", base_currency: null },
  warnings: [],
};

const health = {
  data: {
    source: "classpath-fixture",
    network: false,
    connector_version: "fixture-1",
    quarantined_count: 1,
    channels: [
      { channel: "orders", source: "not-wired", accepted_count: 0, rejected_count: 0, duplicate_count: 0, stale: false, conflict: false, quality_millis: 0, status: "NOT_WIRED" },
      { channel: "prices", source: "fixture-market-data", accepted_count: 2, rejected_count: 1, duplicate_count: 1, stale: false, conflict: true, quality_millis: 0, status: "CONFLICT" },
    ],
  },
  meta: { environment: "DEMO", base_currency: null },
  warnings: [],
};

const audit = {
  data: {
    durable: false,
    chain_valid: true,
    event_count: 1,
    head_hash: "c".repeat(64),
    daily_digest: "d".repeat(64),
    daily_digest_sealed: true,
    events: [],
  },
  meta: { environment: "DEMO", base_currency: null },
  warnings: [],
};

function jsonFor(path: string) {
  if (path.includes("overview")) return overview;
  if (path.includes("configurations")) return configuration;
  if (path.includes("decisions")) return decision;
  if (path.includes("data-health")) return health;
  if (path.includes("audit")) return audit;
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
    expect(screen.getByText("50000 EUR")).toBeInTheDocument();
    expect(await screen.findByTestId("decision")).toHaveTextContent("NO-TRADE");
    expect(screen.getByTestId("decision")).toHaveTextContent("CAPABILITY_UNVERIFIED");
    expect(screen.getByTestId("policy")).toHaveTextContent("MISSING_INTENT");
    expect(screen.getByTestId("policy")).toHaveTextContent("Broker chiamato: no");
    expect(screen.getByTestId("signal")).toHaveTextContent("direzione NONE");
    expect(screen.getByTestId("signal")).toHaveTextContent("eseguibile: no");
    expect(screen.getByTestId("account-card")).toHaveTextContent("Demo eToro");
    expect(screen.getByTestId("account-card")).toHaveTextContent("Connessa");
    expect(screen.getByTestId("account-card")).toHaveTextContent("no");
    expect(screen.getByTestId("account-card")).toHaveTextContent("Conto Demo delle chiavi in backend/.env");
    expect(screen.getByTestId("positions")).toHaveTextContent("Posizioni non disponibili");
    expect(screen.getByTestId("account-card")).toHaveTextContent("Cassa broker");
    expect(screen.getByTestId("account-card")).toHaveTextContent("non disponibile");
    expect(screen.queryByRole("button", { name: /compra|vendi|buy|sell/i })).not.toBeInTheDocument();
  });

  it("shows the demo account and broker cash amount", async () => {
    const withCash = {
      ...overview,
      data: {
        ...overview.data,
        broker_connected: true,
        broker_cash: { minor_units: 28035, currency: "USD" },
        assigned_budget: { minor_units: 50000, currency: "EUR" },
        available_cash: null,
        ledger_status: "FX_UNAVAILABLE",
        fx_status: "UNCONFIGURED",
        account_label: "demo.user",
        positions: [
          { instrument_id: "1002", quantity: "0.049485", value: { minor_units: 10000, currency: "USD" } },
        ],
      },
    };
    vi.stubGlobal("fetch", (input: string) => Promise.resolve({
      ok: true,
      status: 200,
      json: async () => (String(input).includes("overview") ? withCash : jsonFor(String(input))),
    }));
    render(
      <MemoryRouter initialEntries={["/"]}>
        <AppRoutes />
      </MemoryRouter>,
    );
    const card = await screen.findByTestId("account-card");
    expect(card).toHaveTextContent("Demo eToro");
    expect(card).toHaveTextContent("sì");
    expect(card).toHaveTextContent("demo.user");
    expect(card).toHaveTextContent("280,35 USD");
    const positions = screen.getByTestId("positions");
    expect(positions).toHaveTextContent("1002");
    expect(positions).toHaveTextContent("0.049485");
    expect(positions).toHaveTextContent("100,00 USD");
    expect(positions).not.toHaveTextContent("Nessuna posizione");
    expect(screen.getByTestId("cash-alignment")).toHaveTextContent("cassa USD, budget EUR");
    expect(screen.getByTestId("cash-alignment")).toHaveTextContent("Nessun cambio configurato");
    expect(screen.getByText("50000 EUR")).toBeInTheDocument();
    expect(card).not.toHaveTextContent("Conto Demo delle chiavi");
  });

  it("shows an empty demo position list when the read has none", async () => {
    const empty = {
      ...overview,
      data: { ...overview.data, broker_connected: true, positions: [] },
    };
    vi.stubGlobal("fetch", (input: string) => Promise.resolve({
      ok: true,
      status: 200,
      json: async () => (String(input).includes("overview") ? empty : jsonFor(String(input))),
    }));
    render(
      <MemoryRouter initialEntries={["/"]}>
        <AppRoutes />
      </MemoryRouter>,
    );
    expect(await screen.findByTestId("positions")).toHaveTextContent("Nessuna posizione");
    expect(screen.queryByRole("button", { name: /compra|vendi|buy|sell/i })).not.toBeInTheDocument();
  });

  it("shows the active configuration version", async () => {
    renderAt("/configurazione");
    expect(await screen.findByText("daniele")).toBeInTheDocument();
    expect(screen.getByText("2026-11-27T00:00:00Z")).toBeInTheDocument();
    expect(screen.getByText(/Budget 500 EUR/)).toBeInTheDocument();
    expect(screen.getByText("50000 EUR")).toBeInTheDocument();
    expect(screen.getByText("10%, block")).toBeInTheDocument();
    expect(screen.getByText("days")).toBeInTheDocument();
    expect(screen.getByText("MSCI World total_return")).toBeInTheDocument();
    expect(screen.getByText("config/observation-v1.yaml")).toBeInTheDocument();
  });

  it("keeps chat read only", async () => {
    renderAt("/chat");
    expect(await screen.findByTestId("operational-state")).toHaveTextContent("OBSERVATION");
    const input = screen.getByLabelText("Messaggio");
    expect(input).toBeDisabled();
    expect(screen.getByText(/non crea ordini/i)).toBeInTheDocument();
  });

  it("shows fixture channels without a network call", async () => {
    renderAt("/dati");
    expect(await screen.findByRole("heading", { name: "Dati e agente" })).toBeInTheDocument();
    expect(screen.getByText("prices")).toBeInTheDocument();
    expect(screen.getByText("CONFLICT")).toBeInTheDocument();
    expect(screen.getByText("NOT_WIRED")).toBeInTheDocument();
    expect(screen.getByText(/Rete: no/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /compra|vendi|buy|sell/i })).not.toBeInTheDocument();
  });

  it("shows the audit chain without a durable store", async () => {
    renderAt("/audit");
    expect(await screen.findByRole("heading", { name: "Audit" })).toBeInTheDocument();
    expect(screen.getByText(/Catena: integra/)).toBeInTheDocument();
    expect(screen.getByText(/Storage esterno: non collegato/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /compra|vendi|buy|sell/i })).not.toBeInTheDocument();
  });

  it("has no order action on the portfolio route", async () => {
    renderAt("/portafoglio");
    expect(screen.getByRole("heading", { name: "Portafoglio" })).toBeInTheDocument();
    expect(await screen.findByText(/Stato ledger: UNOBSERVED/)).toBeInTheDocument();
    expect(screen.getByText(/Registrazioni: 0/)).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
