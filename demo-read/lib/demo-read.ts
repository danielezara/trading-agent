import {
  BENCHMARK_SYMBOL,
  BUDGET_EUR_MINOR,
  type BenchmarkQuote,
  type ProgramBook,
  buildPosition,
  changeBps,
  missingBenchmark,
  parseBenchmarkId,
  parseEurUsdRate,
  parseReferenceClose,
  selectProgramPositions,
  summarize,
  tick,
} from "./program-book.ts";

export const DEMO_PORTFOLIO_URL = "https://public-api.etoro.com/api/v1/trading/info/demo/portfolio";
export const DEMO_RATES_URL = "https://public-api.etoro.com/api/v1/market-data/instruments/rates";
export const DEMO_INSTRUMENTS_URL = "https://public-api.etoro.com/api/v1/market-data/instruments";
export const DEMO_BALANCES_URL = "https://public-api.etoro.com/api/v1/balances?accountTypes=Trading,Cash&expand=equityDetails&includeZeroBalances=true";
export const DEMO_HISTORY_URL = "https://public-api.etoro.com/api/v1/trading/info/trade/demo/history";
const CASH_TRANSACTIONS_PREFIX = "https://public-api.etoro.com/api/v1/money/accounts/cash/";
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

export type CashMovement = {
  direction: "credit" | "debit";
  status: string;
  kind: string;
  minorUnits: number;
  currency: string;
  postedAt: string | null;
};

export type ClosedTrade = {
  instrumentId: string | null;
  side: string | null;
  quantity: string | null;
  netProfit: string | null;
  closedAt: string | null;
};

export type DemoOverview = {
  platform: "Demo eToro";
  operationalState: "OBSERVATION";
  tradingEnabled: false;
  connected: boolean;
  accountLabel: string | null;
  brokerCash: BrokerCash | null;
  positions: DemoPosition[] | null;
  cashMovements: CashMovement[] | null;
  closedTrades: ClosedTrade[] | null;
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
  quotes?: boolean;
};

export async function readDemoOverview(options: ReadOptions): Promise<DemoOverview> {
  const now = options.now ?? (() => new Date());
  const apiKey = options.env.ETORO_DEMO_API_KEY?.trim() ?? "";
  const userKey = options.env.ETORO_DEMO_USER_KEY?.trim() ?? "";
  if (!apiKey || !userKey) {
    return view(false, null, null, null, null, null, now(), "KEYS_MISSING");
  }
  const outcome = await readOnce(options.fetchImpl, apiKey, userKey, options.sleep ?? defaultSleep);
  if (outcome.kind === "pass") {
    const sleep = options.sleep ?? defaultSleep;
    const positions = options.quotes === false
      ? outcome.positions
      : await enrichPositions(outcome.positions, options.fetchImpl, apiKey, userKey, sleep);
    const cashMovements = await readCashMovements(options.fetchImpl, apiKey, userKey, sleep);
    const closedTrades = await readClosedTrades(options.fetchImpl, apiKey, userKey, sleep, now());
    return view(true, outcome.accountLabel, outcome.brokerCash, positions, cashMovements, closedTrades, now(), null);
  }
  return view(false, null, null, null, null, null, now(), outcome.errorClass);
}

function view(
  connected: boolean,
  accountLabel: string | null,
  brokerCash: BrokerCash | null,
  positions: DemoPosition[] | null,
  cashMovements: CashMovement[] | null,
  closedTrades: ClosedTrade[] | null,
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
    cashMovements,
    closedTrades,
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
      displayName: names.get(position.instrumentId)?.name ?? null,
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

export function parseInstrumentNames(body: string): Map<string, { name: string; symbol: string | null }> {
  const rows = arrayField(body, "instrumentDisplayDatas");
  const names = new Map<string, { name: string; symbol: string | null }>();
  for (const item of rows) {
    const instrumentId = instrumentIdOf(item.instrumentID);
    const name = typeof item.instrumentDisplayName === "string" ? item.instrumentDisplayName.trim() : "";
    const symbol = typeof item.symbolFull === "string" && /^[A-Z0-9.]{1,16}$/.test(item.symbolFull.trim()) ? item.symbolFull.trim() : null;
    if (!instrumentId || instrumentId === "0" || !name) {
      continue;
    }
    names.set(instrumentId, { name, symbol });
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

async function readCashMovements(
  fetchImpl: FetchLike,
  apiKey: string,
  userKey: string,
  sleep: (ms: number) => Promise<void>,
): Promise<CashMovement[] | null> {
  const balances = await readBody(fetchImpl, DEMO_BALANCES_URL, apiKey, userKey, sleep);
  if (balances === null) {
    return null;
  }
  const ids = cashAccountIds(balances);
  if (ids === null) {
    return null;
  }
  const rows: CashMovement[] = [];
  for (const id of ids) {
    const body = await readBody(fetchImpl, cashTransactionsUrl(id), apiKey, userKey, sleep);
    if (body === null) {
      return null;
    }
    const page = parseCashMovements(body);
    if (page === null) {
      return null;
    }
    rows.push(...page);
  }
  return rows;
}

async function readClosedTrades(
  fetchImpl: FetchLike,
  apiKey: string,
  userKey: string,
  sleep: (ms: number) => Promise<void>,
  now: Date,
): Promise<ClosedTrade[] | null> {
  const minDate = new Date(Date.UTC(now.getUTCFullYear() - 1, now.getUTCMonth(), now.getUTCDate() + 1));
  const day = minDate.toISOString().slice(0, 10);
  const body = await readBody(fetchImpl, `${DEMO_HISTORY_URL}?minDate=${day}&page=1&pageSize=100`, apiKey, userKey, sleep);
  if (body === null) {
    return null;
  }
  return parseClosedTrades(body);
}

function cashTransactionsUrl(id: string): string {
  if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(id)) {
    throw new Error("cash account id is not a uuid");
  }
  const url = `${CASH_TRANSACTIONS_PREFIX}${id}/transactions?pageSize=50`;
  if (url.includes("/real")) {
    throw new Error("real trading path is forbidden");
  }
  return url;
}

export function cashAccountIds(body: string): string[] | null {
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    return null;
  }
  if (!isRecord(root) || !Array.isArray(root.balances)) {
    return null;
  }
  const ids: string[] = [];
  for (const row of root.balances) {
    if (!isRecord(row) || row.accountType !== "Cash" || typeof row.accountId !== "string") {
      continue;
    }
    if (/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(row.accountId) && !ids.includes(row.accountId)) {
      ids.push(row.accountId);
    }
  }
  return ids;
}

export function parseCashMovements(body: string): CashMovement[] | null {
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    return null;
  }
  if (!isRecord(root) || !Array.isArray(root.results)) {
    return null;
  }
  const rows: CashMovement[] = [];
  for (const item of root.results) {
    if (!isRecord(item)) {
      continue;
    }
    const direction = item.direction === "credit" || item.direction === "debit" ? item.direction : null;
    const status = token(item.status);
    const kind = token(item.transactionType);
    const currency = typeof item.currency === "string" && /^[A-Z]{3}$/.test(item.currency) ? item.currency : null;
    const minorUnits = signedMinor(item.amount);
    if (!direction || !status || !kind || !currency || minorUnits === null) {
      continue;
    }
    rows.push({
      direction,
      status,
      kind,
      minorUnits,
      currency,
      postedAt: quoteTime(item.postedAt),
    });
  }
  return rows;
}

export function parseClosedTrades(body: string): ClosedTrade[] | null {
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    return null;
  }
  if (!Array.isArray(root)) {
    return null;
  }
  const rows: ClosedTrade[] = [];
  for (const item of root) {
    if (!isRecord(item) || !positiveId(item.positionId)) {
      continue;
    }
    rows.push({
      instrumentId: positiveId(item.instrumentId),
      side: item.isBuy === true ? "BUY" : item.isBuy === false ? "SELL" : null,
      quantity: quantity(item.units),
      netProfit: decimalText(item.netProfit),
      closedAt: quoteTime(item.closeTimestamp),
    });
  }
  return rows;
}

function positiveId(value: unknown): string | null {
  if (typeof value !== "number" || !Number.isInteger(value) || value <= 0) {
    return null;
  }
  return String(value);
}

function token(value: unknown): string | null {
  return typeof value === "string" && /^[A-Za-z]{1,64}$/.test(value) ? value : null;
}

function signedMinor(value: unknown): number | null {
  const numeric = typeof value === "number" ? value : typeof value === "string" ? Number(value) : Number.NaN;
  if (!Number.isFinite(numeric)) {
    return null;
  }
  const cents = Math.trunc(numeric * 100);
  return Number.isSafeInteger(cents) ? cents : null;
}

const SEARCH_URL = "https://public-api.etoro.com/api/v1/market-data/search";
const CANDLES_URL = "https://public-api.etoro.com/api/v1/market-data/instruments/%s/history/candles/desc/FiveMinutes/100";

export async function readProgramBook(options: ReadOptions): Promise<ProgramBook> {
  const now = options.now ?? (() => new Date());
  const apiKey = options.env.ETORO_DEMO_API_KEY?.trim() ?? "";
  const userKey = options.env.ETORO_DEMO_USER_KEY?.trim() ?? "";
  if (!apiKey || !userKey) {
    return closedBook(now(), "KEYS_MISSING");
  }
  const sleep = options.sleep ?? defaultSleep;
  const portfolio = await readBody(options.fetchImpl, DEMO_PORTFOLIO_URL, apiKey, userKey, sleep);
  if (portfolio === null) {
    return closedBook(now(), "PORTFOLIO");
  }
  const selected = selectProgramPositions(portfolio);
  if (selected === null) {
    return closedBook(now(), "RESPONSE_INVALID");
  }
  const balances = await readBody(options.fetchImpl, DEMO_BALANCES_URL, apiKey, userKey, sleep);
  const eurUsdRate = balances === null ? null : parseEurUsdRate(balances);
  const search = await readBody(
    options.fetchImpl,
    `${SEARCH_URL}?internalSymbolFull=${BENCHMARK_SYMBOL}&pageSize=5`,
    apiKey,
    userKey,
    sleep,
  );
  const resolved = search === null ? null : parseBenchmarkId(search, BENCHMARK_SYMBOL);
  const ids = [...new Set([...selected.map((row) => row.instrumentId), ...(resolved ? [resolved.instrumentId] : [])])];
  const quotes = new Map<string, { price: string | null; time: string | null }>();
  const names = new Map<string, { name: string; symbol: string | null }>();
  if (ids.length > 0) {
    const query = `instrumentIds=${ids.join(",")}`;
    const [ratesBody, namesBody] = await Promise.all([
      readBody(options.fetchImpl, `${DEMO_RATES_URL}?${query}`, apiKey, userKey, sleep),
      readBody(options.fetchImpl, `${DEMO_INSTRUMENTS_URL}?${query}`, apiKey, userKey, sleep),
    ]);
    for (const [id, quote] of parseRates(ratesBody ?? "")) {
      quotes.set(id, quote);
    }
    for (const [id, label] of parseInstrumentNames(namesBody ?? "")) {
      names.set(id, label);
    }
  }
  const positions = selected.map((row) => {
    const quote = quotes.get(row.instrumentId);
    const label = names.get(row.instrumentId);
    return buildPosition(row, quote?.price ?? null, quote?.time ?? null, label?.name ?? null, eurUsdRate, label?.symbol ?? null);
  });
  const openAt = selected.map((row) => row.openAt).filter((value): value is string => value !== null).sort()[0] ?? null;
  const benchmark = await benchmarkQuote(options.fetchImpl, apiKey, userKey, sleep, resolved, quotes, openAt);
  return {
    budgetEurMinor: BUDGET_EUR_MINOR,
    connected: true,
    errorClass: null,
    positions,
    ...summarize(positions, eurUsdRate),
    eurUsdRate,
    benchmark,
    readAt: now().toISOString(),
  };
}

async function benchmarkQuote(
  fetchImpl: FetchLike,
  apiKey: string,
  userKey: string,
  sleep: (ms: number) => Promise<void>,
  resolved: { instrumentId: string; name: string | null } | null,
  quotes: Map<string, { price: string | null; time: string | null }>,
  openAt: string | null,
): Promise<BenchmarkQuote> {
  if (!resolved) {
    return missingBenchmark();
  }
  const quote = quotes.get(resolved.instrumentId);
  const bid = quote?.price ?? null;
  let referencePrice: string | null = null;
  if (openAt) {
    const body = await readBody(fetchImpl, candleUrl(resolved.instrumentId), apiKey, userKey, sleep);
    referencePrice = body === null ? null : parseReferenceClose(body, openAt);
  }
  return {
    resolved: true,
    instrumentId: resolved.instrumentId,
    name: resolved.name,
    bid,
    quoteTime: quote?.time ?? null,
    referencePrice,
    sinceReference: tick(referencePrice, bid),
    changeBps: changeBps(referencePrice, bid),
  };
}

function candleUrl(id: string): string {
  if (!/^[1-9]\d{0,9}$/.test(id)) {
    throw new Error("instrument id is not a positive integer");
  }
  const url = CANDLES_URL.replace("%s", id);
  if (url !== CANDLES_URL.replace("%s", id) || url.includes("/real") || url.includes("%s")) {
    throw new Error("candle url is not the five minute history");
  }
  return url;
}

function closedBook(at: Date, errorClass: string): ProgramBook {
  return {
    budgetEurMinor: BUDGET_EUR_MINOR,
    connected: false,
    errorClass,
    positions: null,
    valueUsdMinor: null,
    valueEurMinor: null,
    changeUsdMinor: null,
    changeEurMinor: null,
    lossEurMinor: null,
    lossBpsOfBudget: null,
    eurUsdRate: null,
    benchmark: missingBenchmark(),
    readAt: at.toISOString(),
  };
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
