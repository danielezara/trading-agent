export type Warning = { code: string; detail: string };

export type Envelope<T> = {
  data: T;
  meta: { environment: string; base_currency: string | null };
  warnings: Warning[];
};

export type Money = { minor_units: number; currency: string } | null;

export type Overview = {
  operational_state: string;
  trading_enabled: boolean;
  equity: Money;
  broker_cash: Money;
  assigned_budget: Money;
  available_cash: Money;
  reserved_cash: Money;
  cash_buffer: Money;
  ledger_status: string;
  configuration_version_id: string;
  configuration_version_number: number;
};

export type Budget = {
  base_currency: string | null;
  assigned_budget_minor: number | null;
  cash_buffer_minor: number | null;
  max_positions: number | null;
};

export type Configuration = {
  version_id: string;
  version_number: number;
  author: string;
  created_at: string;
  reason: string;
  content_sha256: string;
  active: boolean;
  budget: Budget;
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
