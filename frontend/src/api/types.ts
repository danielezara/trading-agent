export type Warning = { code: string; detail: string };

export type Envelope<T> = {
  data: T;
  meta: { environment: string; base_currency: string | null };
  warnings: Warning[];
};

export type Money = { minor_units: number; currency: string } | null;

export type CashBalance = {
  account_type: string;
  minor_units: number;
  currency: string;
};

export type DemoPosition = {
  instrument_id: string;
  quantity: string | null;
  value: Money;
  quote_price?: string | null;
  quote_time?: string | null;
  display_name?: string | null;
  asset_class?: "stock" | "etf" | "other" | "unknown" | null;
  allowlist?: "inside" | "outside" | "unknown" | null;
  official_type?: string | null;
};

export type ClosedTrade = {
  position_id: string;
  instrument_id: string | null;
  side: "BUY" | "SELL" | null;
  quantity: string | null;
  net_profit: string | null;
  closed_at: string | null;
};

export type DemoOrder = {
  order_id: string;
  instrument_id: string | null;
  side: "BUY" | "SELL" | null;
  quantity: string | null;
  value: Money;
  opened_at: string | null;
};

export type Overview = {
  operational_state: string;
  trading_enabled: boolean;
  equity: Money;
  broker_cash: Money;
  cash_balances?: CashBalance[] | null;
  assigned_budget: Money;
  available_cash: Money;
  reserved_cash: Money;
  cash_buffer: Money;
  ledger_status: string;
  fx_status?: string;
  fx_source?: string | null;
  fx_version?: string | null;
  fx_observed_at?: string | null;
  fx_haircut_bps?: number | null;
  journal_entry_count: number;
  journal_balanced: boolean;
  broker_connected: boolean;
  last_probe_at: string | null;
  probe_error_class: string | null;
  account_label: string | null;
  positions: DemoPosition[] | null;
  orders: DemoOrder[] | null;
  executions: DemoOrder[] | null;
  closed_trades?: ClosedTrade[] | null;
  configuration_version_id: string;
  configuration_version_number: number;
  reconciliation?: {
    status: string;
    classes: string[];
    order_sent: boolean;
    operational_state: string;
  };
};

export type Decision = {
  action: string;
  reason_codes: string[];
  summary: string;
  input_hash: string;
  evaluated_at: string;
  config_version_id: string;
  config_content_sha256: string;
  audit_sequence: number;
  audit_event_hash: string;
  policy: {
    accepted: boolean;
    reason: string;
    broker_called: boolean;
  };
  signal?: {
    signal_id: string;
    origin: string;
    direction: string;
    instrument_id: string | null;
    confidence_millis: number;
    orderable: boolean;
    rationale: string;
  };
};

export type AuditEvent = {
  sequence: number;
  occurred_at: string;
  correlation_id: string;
  event_type: string;
  config_version_id: string;
  config_content_sha256: string;
  event_hash: string;
};

export type AuditChain = {
  durable: boolean;
  chain_valid: boolean;
  event_count: number;
  head_hash: string | null;
  daily_digest: string | null;
  daily_digest_sealed: boolean;
  events: AuditEvent[];
};

export type ChatCitation = {
  citation_id: string;
  source_type: string;
  resource_id: string;
  label: string;
  field: string;
  as_of: string | null;
};

export type ChatTurn = {
  executed: false;
  refused: boolean;
  text: string;
  environment: "DEMO";
  as_of: string;
  citations: ChatCitation[];
};

export type DataChannel = {
  channel: string;
  source: string;
  accepted_count: number;
  rejected_count: number;
  duplicate_count: number;
  stale: boolean;
  conflict: boolean;
  quality_millis: number;
  status: string;
  category?: string | null;
  event_time?: string | null;
  ingested_at?: string | null;
  license?: string | null;
};

export type DataHealth = {
  source: string;
  network: boolean;
  connector_version: string;
  quarantined_count: number;
  channels: DataChannel[];
};

export type Budget = {
  base_currency: string | null;
  assigned_budget_minor: number | null;
  cash_buffer_minor: number | null;
  max_positions: number | null;
};

export type EconomicView = {
  max_loss_bps: number | null;
  max_loss_action: string | null;
  horizon: string | null;
  allowlist_universe: string | null;
  allowlist_direction: string | null;
  leverage: number | null;
  benchmark_name: string | null;
  benchmark_return_basis: string | null;
  technical_calibratable: boolean;
  source_path: string;
  risk_per_trade_bps?: number | null;
  instrument_exposure_bps?: number | null;
  min_cash_bps?: number | null;
  entry_rule?: string | null;
  price_source?: string | null;
  decision_bar_seconds?: number | null;
  max_holding_count?: number | null;
};

export type Configuration = {
  version_id: string;
  version_number: number;
  author: string;
  created_at: string;
  reason: string;
  content_sha256: string;
  valid_from_bar_open: string;
  active: boolean;
  budget: Budget;
  economic: EconomicView;
};

export type OperationalState = {
  state: string;
  since: string;
  reason: string;
  generation: number;
  actions: {
    new_entries: boolean;
    local_exits: boolean;
    cancel: boolean;
    reconcile_permitted: boolean;
    reconcile_wired: boolean;
  };
};
