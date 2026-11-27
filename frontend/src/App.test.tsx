import { fireEvent, render, screen } from "@testing-library/react";
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
    orders: null,
    executions: null,
    configuration_version_id: "11111111-1111-1111-1111-111111111111",
    configuration_version_number: 1,
    reconciliation: {
      status: "UNAVAILABLE",
      classes: [],
      order_sent: false,
      operational_state: "OBSERVATION",
    },
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
      { channel: "prices", source: "fixture-market-data", accepted_count: 2, rejected_count: 1, duplicate_count: 1, stale: true, conflict: true, quality_millis: 0, status: "CONFLICT", category: "market-data", event_time: "2026-11-27T00:00:10Z", ingested_at: "2026-11-27T00:00:11Z", license: "fixture-unverified" },
      { channel: "news", source: "fixture-news", accepted_count: 1, rejected_count: 0, duplicate_count: 0, stale: true, conflict: false, quality_millis: 0, status: "STALE", category: "news", event_time: "2026-11-27T00:00:00Z", ingested_at: "2026-11-27T00:00:02Z", license: "fixture-unverified" },
      { channel: "macro", source: "fixture-macro", accepted_count: 1, rejected_count: 0, duplicate_count: 0, stale: true, conflict: false, quality_millis: 0, status: "STALE", category: "macro", event_time: "2026-11-01T00:00:00Z", ingested_at: "2026-11-27T00:00:00Z", license: "fixture-unverified" },
      { channel: "fundamentals", source: "fixture-fundamentals", accepted_count: 1, rejected_count: 0, duplicate_count: 0, stale: true, conflict: false, quality_millis: 0, status: "STALE", category: "fundamentals", event_time: "2026-11-01T00:00:00Z", ingested_at: "2026-11-27T00:00:00Z", license: "fixture-unverified" },
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
  if (path.includes("/chat")) {
    const order = /compra|vendi|buy|sell|ignora/i.test(path);
    return {
      data: {
        executed: false,
        refused: order,
        text: order
          ? "Nessuna azione è stata eseguita. La chat è solo informativa e non invia ordini."
          : "Ambiente DEMO. Decisione NO-TRADE. Motivi: CAPABILITY_UNVERIFIED.",
        environment: "DEMO",
        as_of: "2026-11-27T00:00:30Z",
        citations: order ? [] : [{
          citation_id: "1",
          source_type: "decision",
          resource_id: "/api/v1/decisions/latest",
          label: "Decisione registrata",
          field: "reason_codes",
          as_of: "2026-11-27T00:00:30Z",
        }],
      },
      meta: { environment: "DEMO", base_currency: null },
      warnings: [],
    };
  }
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
    expect(screen.getByTestId("orders")).toHaveTextContent("Ordini non disponibili");
    expect(screen.getByTestId("executions")).toHaveTextContent("Esecuzioni non disponibili");
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
        orders: [
          {
            order_id: "5669649",
            instrument_id: "100043",
            side: "BUY",
            quantity: "688.231246",
            value: { minor_units: 10000, currency: "USD" },
            opened_at: "2024-06-06T08:07:25.083Z",
          },
        ],
        executions: [
          {
            order_id: "88",
            instrument_id: "1002",
            side: null,
            quantity: "0.049485",
            value: null,
            opened_at: "2024-06-07T09:00:00Z",
          },
        ],
        closed_trades: [
          {
            position_id: "7",
            instrument_id: "1002",
            side: "BUY",
            quantity: "1.5",
            net_profit: "-2.5",
            closed_at: "2026-11-01T12:00:00Z",
          },
          {
            position_id: "8",
            instrument_id: null,
            side: null,
            quantity: null,
            net_profit: null,
            closed_at: null,
          },
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
    const orders = screen.getByTestId("orders");
    expect(orders).toHaveTextContent("5669649");
    expect(orders).toHaveTextContent("100043");
    expect(orders).toHaveTextContent("acquisto");
    expect(orders).toHaveTextContent("688.231246");
    expect(orders).toHaveTextContent("100,00 USD");
    const executions = screen.getByTestId("executions");
    expect(executions).toHaveTextContent("88");
    expect(executions).toHaveTextContent("0.049485");
    expect(executions).toHaveTextContent("non disponibile");
    const closed = screen.getByTestId("closed-trades");
    expect(closed).toHaveTextContent("7");
    expect(closed).toHaveTextContent("1002");
    expect(closed).toHaveTextContent("acquisto");
    expect(closed).toHaveTextContent("-2.5");
    expect(closed).toHaveTextContent("2026-11-01T12:00:00Z");
    expect(screen.getAllByTestId("closed-profit").map((cell) => cell.textContent)).toEqual(["-2.5", ""]);
    expect(screen.getByTestId("cash-alignment")).toHaveTextContent("cassa USD, budget EUR");
    expect(screen.getByTestId("cash-alignment")).toHaveTextContent("Il riferimento BCE manca o è stale");
    expect(screen.getByText("50000 EUR")).toBeInTheDocument();
    expect(card).not.toHaveTextContent("Conto Demo delle chiavi");
  });

  it("shows an empty demo position list when the read has none", async () => {
    const empty = {
      ...overview,
      data: { ...overview.data, broker_connected: true, positions: [], orders: [], executions: [], closed_trades: [] },
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
    expect(screen.getByTestId("orders")).toHaveTextContent("Nessun ordine Demo");
    expect(screen.getByTestId("executions")).toHaveTextContent("Nessuna esecuzione Demo");
    expect(screen.getByTestId("closed-trades")).toHaveTextContent("Nessuna chiusura Demo");
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
    expect(input).toBeEnabled();
    expect(screen.getByText(/non crea ordini/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /compra|vendi|buy|sell/i })).not.toBeInTheDocument();
    fireEvent.change(input, { target: { value: "compra adesso" } });
    fireEvent.click(screen.getByRole("button", { name: "Chiedi" }));
    expect(await screen.findByTestId("chat-answer")).toHaveTextContent("Nessuna azione");
    expect(screen.getByTestId("chat-executed")).toHaveTextContent("nessuna azione");
    expect(screen.queryByTestId("chat-citations")).not.toBeInTheDocument();
    fireEvent.change(input, { target: { value: "perché la decisione" } });
    fireEvent.click(screen.getByRole("button", { name: "Chiedi" }));
    expect(await screen.findByTestId("chat-citations")).toHaveTextContent("/api/v1/decisions/latest");
    expect(screen.getByTestId("chat-answer")).toHaveTextContent("NO-TRADE");
    expect(screen.queryByRole("button", { name: /compra|vendi|buy|sell/i })).not.toBeInTheDocument();
  });

  it("shows fixture channels without a network call", async () => {
    renderAt("/dati");
    expect(await screen.findByRole("heading", { name: "Dati e agente" })).toBeInTheDocument();
    expect(screen.getByText("prices")).toBeInTheDocument();
    expect(screen.getByText("CONFLICT")).toBeInTheDocument();
    expect(screen.getByText("NOT_WIRED")).toBeInTheDocument();
    expect(screen.getByText(/Rete: no/)).toBeInTheDocument();
    const market = screen.getByTestId("market-data");
    expect(market).toHaveTextContent("Fixture prezzi stale");
    expect(market).toHaveTextContent("fixture-market-data");
    expect(market).toHaveTextContent("2026-11-27T00:00:10Z");
    expect(market).toHaveTextContent("fixture-unverified");
    expect(market).toHaveTextContent("NO-TRADE");
    const news = screen.getByTestId("news-data");
    expect(news).toHaveTextContent("Fixture news stale");
    expect(news).toHaveTextContent("fixture-news");
    expect(news).toHaveTextContent("2026-11-27T00:00:00Z");
    expect(news).toHaveTextContent("fixture-unverified");
    expect(news).toHaveTextContent("NO-TRADE");
    expect(news).not.toHaveTextContent("synthetic-headline");
    const macro = screen.getByTestId("macro-data");
    expect(macro).toHaveTextContent("Fixture macro stale");
    expect(macro).toHaveTextContent("fixture-macro");
    expect(macro).toHaveTextContent("2026-11-01T00:00:00Z");
    expect(macro).toHaveTextContent("NO-TRADE");
    const fundamentals = screen.getByTestId("fundamentals-data");
    expect(fundamentals).toHaveTextContent("Fixture fondamentali stale");
    expect(fundamentals).toHaveTextContent("fixture-fundamentals");
    expect(fundamentals).toHaveTextContent("fixture-unverified");
    expect(fundamentals).toHaveTextContent("NO-TRADE");
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
    expect(screen.getByTestId("reconciliation")).toHaveTextContent("Riconciliazione non disponibile");
    expect(screen.getByTestId("reconciliation")).toHaveTextContent("Nessun ordine");
    expect(screen.getByTestId("reconciliation")).toHaveTextContent("OBSERVATION");
    expect(screen.getByText(/Registrazioni: 0/)).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("shows a live quote beside a position and leaves a missing quote blank", async () => {
    const calls: string[] = [];
    const withQuotes = {
      ...overview,
      data: {
        ...overview.data,
        broker_connected: true,
        positions: [
          {
            instrument_id: "6270",
            quantity: "1",
            value: null,
            quote_price: "1.25",
            quote_time: "2026-11-27T09:30:00Z",
            display_name: "Apple",
            asset_class: "stock",
            allowlist: "inside",
            official_type: "Stocks",
          },
          {
            instrument_id: "100032",
            quantity: "2",
            value: null,
            quote_price: null,
            quote_time: null,
            display_name: null,
            asset_class: "other",
            allowlist: "outside",
            official_type: "Crypto",
          },
        ],
      },
    };
    vi.stubGlobal("fetch", (input: string) => {
      calls.push(String(input));
      return Promise.resolve({
        ok: true,
        status: 200,
        json: async () => (String(input).includes("overview") ? withQuotes : jsonFor(String(input))),
      });
    });
    render(
      <MemoryRouter initialEntries={["/"]}>
        <AppRoutes />
      </MemoryRouter>,
    );
    const prices = await screen.findAllByTestId("quote-price");
    const times = screen.getAllByTestId("quote-time");
    expect(prices[0]).toHaveTextContent("1.25");
    expect(times[0]).toHaveTextContent("2026-11-27T09:30:00Z");
    expect(prices[1].textContent).toBe("");
    expect(times[1].textContent).toBe("");
    const names = screen.getAllByTestId("instrument-name");
    expect(names[0]).toHaveTextContent("Apple");
    expect(names[1].textContent).toBe("");
    expect(screen.getAllByTestId("asset-class").map((cell) => cell.textContent)).toEqual(["Stocks", "Crypto"]);
    expect(screen.getAllByTestId("allowlist").map((cell) => cell.textContent)).toEqual(["inside", "outside"]);
    expect(screen.getByTestId("positions")).toHaveTextContent("100032");
    expect(screen.getByTestId("positions")).toHaveTextContent("6270");
    expect(screen.getByTestId("positions")).toHaveTextContent("100032");
    expect(calls.some((path) => path.includes("news") || path.includes("feeds"))).toBe(false);
    expect(screen.queryByRole("button", { name: /compra|vendi|buy|sell/i })).not.toBeInTheDocument();
  });

  it("keeps a stale live price as no trade and leaves news on the fixture", async () => {
    const liveHealth = {
      ...health,
      data: {
        ...health.data,
        source: "mixed",
        network: true,
        channels: health.data.channels.map((channel) => (
          channel.channel === "prices"
            ? {
              ...channel,
              source: "etoro-public-api",
              accepted_count: 1,
              stale: true,
              conflict: false,
              status: "STALE",
              license: "unverified",
              event_time: "2026-11-27T09:00:00Z",
            }
            : channel
        )),
      },
    };
    vi.stubGlobal("fetch", (input: string) => Promise.resolve({
      ok: true,
      status: 200,
      json: async () => (String(input).includes("data-health") ? liveHealth : jsonFor(String(input))),
    }));
    render(
      <MemoryRouter initialEntries={["/dati"]}>
        <AppRoutes />
      </MemoryRouter>,
    );
    const market = await screen.findByTestId("market-data");
    expect(market).toHaveTextContent("Prezzi di mercato stale");
    expect(market).toHaveTextContent("etoro-public-api");
    expect(market).toHaveTextContent("unverified");
    expect(market).toHaveTextContent("NO-TRADE");
    expect(screen.getByText(/Rete: sì/)).toBeInTheDocument();
    expect(screen.getByText(/News, macro e fondamentali restano fixture/)).toBeInTheDocument();
    expect(screen.getByTestId("news-data")).toHaveTextContent("Fixture news stale");
    expect(screen.queryByRole("button", { name: /compra|vendi|buy|sell/i })).not.toBeInTheDocument();
  });

  it("keeps a missing live price as no trade", async () => {
    const missingHealth = {
      ...health,
      data: {
        ...health.data,
        source: "mixed",
        network: true,
        channels: health.data.channels.map((channel) => (
          channel.channel === "prices"
            ? {
              ...channel,
              source: "classpath-fixture",
              accepted_count: 0,
              stale: false,
              status: "UNUSABLE",
              license: null,
              event_time: null,
            }
            : channel
        )),
      },
    };
    vi.stubGlobal("fetch", (input: string) => Promise.resolve({
      ok: true,
      status: 200,
      json: async () => (String(input).includes("data-health") ? missingHealth : jsonFor(String(input))),
    }));
    render(
      <MemoryRouter initialEntries={["/dati"]}>
        <AppRoutes />
      </MemoryRouter>,
    );
    expect(await screen.findByTestId("market-data")).toHaveTextContent("Canale prezzi assente. La decisione resta NO-TRADE.");
  });
});
