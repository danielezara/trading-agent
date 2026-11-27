export const DEMO_PORTFOLIO_URL = "https://public-api.etoro.com/api/v1/trading/info/demo/portfolio";
export const DEMO_RATES_URL = "https://public-api.etoro.com/api/v1/market-data/instruments/rates";
export const DEMO_INSTRUMENTS_URL = "https://public-api.etoro.com/api/v1/market-data/instruments";
export const DEMO_ACCOUNT_FALLBACK = "Conto Demo delle chiavi in backend/.env";

const LABEL_KEYS = ["username", "userName", "accountId", "account_id"] as const;

export type BrokerCash = {
  minorUnits: number;
  currency: string;
};

export type DemoPosition = {
  instrumentId: string;
  quantity: string | null;
  value: BrokerCash | null;
  displayName: string | null;
  quotePrice: string | null;
  quoteTime: string | null;
};

export type DemoOverview = {
  platform: "Demo eToro";
  operationalState: "OBSERVATION";
  tradingEnabled: false;
  connected: boolean;
  accountLabel: string | null;
  brokerCash: BrokerCash | null;
  positions: DemoPosition[] | null;
  lastProbeAt: string | null;
  probeErrorClass: string | null;
};

type FetchLike = (input: string, init: { method: "GET"; headers: Record<string, string>; redirect: "manual"; signal: AbortSignal; cache: "no-store" }) => Promise<{
  status: number;
  headers: { get(name: string): string | null };
  text(): Promise<string>;
}>;

type ReadOptions = {
  env: Record<string, string | undefined>;
  fetchImpl: FetchLike;
  now?: () => Date;
  sleep?: (ms: number) => Promise<void>;
};

export async function readDemoOverview(options: ReadOptions): Promise<DemoOverview> {
  const now = options.now ?? (() => new Date());
  const apiKey = options.env.ETORO_DEMO_API_KEY?.trim() ?? "";
  const userKey = options.env.ETORO_DEMO_USER_KEY?.trim() ?? "";
  if (!apiKey || !userKey) {
    return view(false, null, null, null, now(), "KEYS_MISSING");
  }
  const outcome = await readOnce(options.fetchImpl, apiKey, userKey, options.sleep ?? defaultSleep);
  if (outcome.kind === "pass") {
    const positions = await enrichPositions(outcome.positions, options.fetchImpl, apiKey, userKey, options.sleep ?? defaultSleep);
    return view(true, outcome.accountLabel, outcome.brokerCash, positions, now(), null);
  }
  return view(false, null, null, null, now(), outcome.errorClass);
}

function view(
  connected: boolean,
  accountLabel: string | null,
  brokerCash: BrokerCash | null,
  positions: DemoPosition[] | null,
  at: Date,
  probeErrorClass: string | null,
): DemoOverview {
  return {
    platform: "Demo eToro",
    operationalState: "OBSERVATION",
    tradingEnabled: false,
    connected,
    accountLabel,
    brokerCash,
    positions,
    lastProbeAt: at.toISOString(),
    probeErrorClass,
  };
}

async function enrichPositions(
  positions: DemoPosition[] | null,
  fetchImpl: FetchLike,
  apiKey: string,
  userKey: string,
  sleep: (ms: number) => Promise<void>,
): Promise<DemoPosition[] | null> {
  if (!positions || positions.length === 0) {
    return positions;
  }
  const ids = [...new Set(positions.map((position) => position.instrumentId))]
    .filter((id) => /^[1-9]\d{0,9}$/.test(id) && Number(id) <= 2147483647)
    .slice(0, 100);
  if (ids.length === 0) {
    return positions;
  }
  const query = `instrumentIds=${ids.join(",")}`;
  const [ratesBody, namesBody] = await Promise.all([
    readBody(fetchImpl, `${DEMO_RATES_URL}?${query}`, apiKey, userKey, sleep),
    readBody(fetchImpl, `${DEMO_INSTRUMENTS_URL}?${query}`, apiKey, userKey, sleep),
  ]);
  const rates = parseRates(ratesBody ?? "");
  const names = parseInstrumentNames(namesBody ?? "");
  return positions.map((position) => {
    const quote = rates.get(position.instrumentId);
    return {
      ...position,
      displayName: names.get(position.instrumentId) ?? null,
      quotePrice: quote?.price ?? null,
      quoteTime: quote?.time ?? null,
    };
  });
}

async function readBody(
  fetchImpl: FetchLike,
  url: string,
  apiKey: string,
  userKey: string,
  sleep: (ms: number) => Promise<void>,
): Promise<string | null> {
  const first = await get(fetchImpl, url, apiKey, userKey);
  if (first.kind === "transport") {
    return null;
  }
  const response = first.status === 429 && retryDelay(first.retryAfter) !== null
    ? await retry(fetchImpl, url, apiKey, userKey, sleep, retryDelay(first.retryAfter)!)
    : first;
  if (response.kind === "transport" || response.status !== 200) {
    return null;
  }
  return response.body;
}

async function readOnce(
  fetchImpl: FetchLike,
  apiKey: string,
  userKey: string,
  sleep: (ms: number) => Promise<void>,
): Promise<{ kind: "pass"; accountLabel: string | null; brokerCash: BrokerCash | null; positions: DemoPosition[] | null } | { kind: "closed"; errorClass: string }> {
  const first = await get(fetchImpl, DEMO_PORTFOLIO_URL, apiKey, userKey);
  if (first.kind === "transport") {
    return { kind: "closed", errorClass: "TRANSPORT" };
  }
  const response = first.status === 429 && retryDelay(first.retryAfter) !== null
    ? await retry(fetchImpl, DEMO_PORTFOLIO_URL, apiKey, userKey, sleep, retryDelay(first.retryAfter)!)
    : first;
  if (response.kind === "transport") {
    return { kind: "closed", errorClass: "TRANSPORT" };
  }
  if (response.status === 429) {
    return { kind: "closed", errorClass: "RATE_LIMITED" };
  }
  return classify(response.status, response.body);
}

async function retry(
  fetchImpl: FetchLike,
  url: string,
  apiKey: string,
  userKey: string,
  sleep: (ms: number) => Promise<void>,
  delayMs: number,
) {
  await sleep(delayMs);
  return get(fetchImpl, url, apiKey, userKey);
}

async function get(fetchImpl: FetchLike, url: string, apiKey: string, userKey: string) {
  try {
    const response = await fetchImpl(url, {
      method: "GET",
      redirect: "manual",
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
      headers: {
        Accept: "application/json",
        "User-Agent": "trading-agent-observation",
        "x-request-id": crypto.randomUUID(),
        "x-api-key": apiKey,
        "x-user-key": userKey,
      },
    });
    if (response.status >= 300 && response.status < 400) {
      return { kind: "transport" as const };
    }
    const body = await response.text();
    return {
      kind: "http" as const,
      status: response.status,
      retryAfter: response.headers.get("retry-after"),
      body: body.length > 2_000_000 ? "" : body,
    };
  } catch {
    return { kind: "transport" as const };
  }
}

function retryDelay(header: string | null): number | null {
  const seconds = Number(header?.trim());
  if (!Number.isInteger(seconds) || seconds < 0 || seconds > 2) {
    return null;
  }
  return seconds * 1000;
}

function classify(status: number, body: string) {
  if (status === 200) {
    const parsed = parsePortfolio(body);
    return parsed ? { kind: "pass" as const, ...parsed } : { kind: "closed" as const, errorClass: "RESPONSE_INVALID" };
  }
  if (status === 401 || status === 403) {
    return { kind: "closed" as const, errorClass: "AUTHENTICATION" };
  }
  if (status >= 400 && status < 500) {
    return { kind: "closed" as const, errorClass: "CLIENT_ERROR" };
  }
  if (status >= 500 && status < 600) {
    return { kind: "closed" as const, errorClass: "SERVER_ERROR" };
  }
  return { kind: "closed" as const, errorClass: "TRANSPORT" };
}

export function parsePortfolio(body: string): { accountLabel: string | null; brokerCash: BrokerCash | null; positions: DemoPosition[] | null } | null {
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    return null;
  }
  if (!isRecord(root)) {
    return null;
  }
  const portfolio = root.clientPortfolio;
  if (!isRecord(portfolio)) {
    return null;
  }
  const cash = brokerCash(portfolio);
  return {
    accountLabel: accountLabel(root, portfolio),
    brokerCash: cash,
    positions: positions(portfolio, cash?.currency ?? currencyCode(portfolio.accountCurrencyId)),
  };
}

function positions(portfolio: Record<string, unknown>, currency: string | null): DemoPosition[] | null {
  if (!Object.prototype.hasOwnProperty.call(portfolio, "positions") || portfolio.positions == null) {
    return [];
  }
  if (!Array.isArray(portfolio.positions)) {
    return null;
  }
  const rows: DemoPosition[] = [];
  for (const item of portfolio.positions) {
    if (!isRecord(item)) {
      continue;
    }
    const instrumentId = instrumentIdOf(item.instrumentID);
    if (!instrumentId) {
      continue;
    }
    const amount = minor(item.amount);
    rows.push({
      instrumentId,
      quantity: quantity(item.units),
      value: currency && amount !== null && amount >= 0 ? { minorUnits: amount, currency } : null,
      displayName: null,
      quotePrice: null,
      quoteTime: null,
    });
  }
  return rows;
}

function instrumentIdOf(value: unknown): string | null {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0) {
    return null;
  }
  return String(value);
}

function quantity(value: unknown): string | null {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return null;
  }
  const text = Object.is(value, -0) ? "0" : value.toString();
  return /^-?\d+(\.\d+)?$/.test(text) ? text : null;
}

function brokerCash(portfolio: Record<string, unknown>): BrokerCash | null {
  const currency = currencyCode(portfolio.accountCurrencyId);
  const minorUnits = minor(portfolio.credit);
  if (!currency || minorUnits === null || minorUnits < 0) {
    return null;
  }
  return { minorUnits, currency };
}

function currencyCode(value: unknown): string | null {
  if (value === undefined || value === null) {
    return "USD";
  }
  if (value === 1) {
    return "USD";
  }
  return null;
}

function minor(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return null;
  }
  const cents = Math.round(value * 100);
  if (!Number.isSafeInteger(cents)) {
    return null;
  }
  return cents;
}

function accountLabel(root: Record<string, unknown>, portfolio: Record<string, unknown>): string | null {
  for (const key of LABEL_KEYS) {
    for (const node of [portfolio, root]) {
      const value = node[key];
      const text = typeof value === "string" ? value.trim() : typeof value === "number" && Number.isInteger(value) ? String(value) : "";
      if (isPublicAccountLabel(text)) {
        return text;
      }
    }
  }
  return null;
}

function isPublicAccountLabel(text: string): boolean {
  return text.length >= 1 && text.length <= 64 && /^[A-Za-z0-9 ._\-@]+$/.test(text);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

export function parseRates(body: string): Map<string, { price: string | null; time: string | null }> {
  const rates = arrayField(body, "rates");
  const parsed = new Map<string, { price: string | null; time: string | null }>();
  for (const item of rates) {
    const instrumentId = instrumentIdOf(item.instrumentID);
    if (!instrumentId || instrumentId === "0") {
      continue;
    }
    const price = decimalText(item.bid) ?? decimalText(item.lastExecution);
    const time = quoteTime(item.date);
    if (price === null && time === null) {
      continue;
    }
    parsed.set(instrumentId, { price, time });
  }
  return parsed;
}

export function parseInstrumentNames(body: string): Map<string, string> {
  const rows = arrayField(body, "instrumentDisplayDatas");
  const names = new Map<string, string>();
  for (const item of rows) {
    const instrumentId = instrumentIdOf(item.instrumentID);
    const name = typeof item.instrumentDisplayName === "string" ? item.instrumentDisplayName.trim() : "";
    if (!instrumentId || instrumentId === "0" || !name) {
      continue;
    }
    names.set(instrumentId, name);
  }
  return names;
}

function arrayField(body: string, field: string): Record<string, unknown>[] {
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    return [];
  }
  if (!isRecord(root) || !Array.isArray(root[field])) {
    return [];
  }
  return root[field].filter(isRecord);
}

function decimalText(value: unknown): string | null {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    return null;
  }
  const text = Object.is(value, -0) ? "0" : value.toString();
  return /^(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?$/i.test(text) ? text : null;
}

function quoteTime(value: unknown): string | null {
  if (typeof value !== "string" || !value.trim() || Number.isNaN(Date.parse(value))) {
    return null;
  }
  return value.trim();
}

export function formatCash(cash: BrokerCash | null): string {
  if (!cash) {
    return "non disponibile";
  }
  const absolute = Math.abs(cash.minorUnits);
  const whole = Math.trunc(absolute / 100).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  const fraction = String(absolute % 100).padStart(2, "0");
  return `${cash.minorUnits < 0 ? "-" : ""}${whole},${fraction} ${cash.currency}`;
}

export function displayAccount(label: string | null): string {
  return label && label.trim() ? label : DEMO_ACCOUNT_FALLBACK;
}
