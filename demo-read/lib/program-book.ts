export const BUDGET_EUR_MINOR = 50_000;
export const BENCHMARK_SYMBOL = "SWDA.L";

export const PROGRAM_ORDERS = [
  { orderId: "397030666", instrumentId: "1001", symbol: "AAPL" },
] as const;

export type Tick = "up" | "down" | "flat";

export type ProgramPosition = {
  orderId: string;
  instrumentId: string;
  symbol: string;
  name: string | null;
  units: string | null;
  openRate: string | null;
  bid: string | null;
  quoteTime: string | null;
  sinceOpen: Tick | null;
  valueUsdMinor: number | null;
  costUsdMinor: number | null;
  changeUsdMinor: number | null;
  valueEurMinor: number | null;
  changeEurMinor: number | null;
};

export type BenchmarkQuote = {
  resolved: boolean;
  instrumentId: string | null;
  name: string | null;
  bid: string | null;
  quoteTime: string | null;
  referencePrice: string | null;
  sinceReference: Tick | null;
  changeBps: number | null;
};

export type ProgramBook = {
  budgetEurMinor: number;
  connected: boolean;
  errorClass: string | null;
  positions: ProgramPosition[] | null;
  valueUsdMinor: number | null;
  valueEurMinor: number | null;
  changeUsdMinor: number | null;
  changeEurMinor: number | null;
  lossEurMinor: number | null;
  lossBpsOfBudget: number | null;
  eurUsdRate: string | null;
  benchmark: BenchmarkQuote;
  readAt: string;
};

export function tick(previous: string | null, next: string | null): Tick | null {
  const from = numberOrNull(previous);
  const to = numberOrNull(next);
  if (from === null || to === null) {
    return null;
  }
  if (to > from) {
    return "up";
  }
  if (to < from) {
    return "down";
  }
  return "flat";
}

export function changeBps(from: string | null, to: string | null): number | null {
  const start = numberOrNull(from);
  const end = numberOrNull(to);
  if (start === null || end === null || start <= 0 || end < 0) {
    return null;
  }
  const bps = Math.round(((end - start) / start) * 10_000);
  return Number.isSafeInteger(bps) ? bps : null;
}

export function usdToEurMinor(usdMinor: number, rate: string | null): number | null {
  const quote = numberOrNull(rate);
  if (quote === null || quote <= 0 || !Number.isSafeInteger(usdMinor)) {
    return null;
  }
  const eur = Math.floor(usdMinor / quote);
  return Number.isSafeInteger(eur) ? eur : null;
}

export function productMinor(units: string | null, price: string | null): number | null {
  const left = numberOrNull(units);
  const right = numberOrNull(price);
  if (left === null || right === null || left < 0 || right < 0) {
    return null;
  }
  const cents = Math.round(left * right * 100);
  return Number.isSafeInteger(cents) ? cents : null;
}

export function moneyMinor(value: unknown): number | null {
  const numeric = numberOrNull(typeof value === "number" || typeof value === "string" ? String(value) : null);
  if (numeric === null || numeric < 0) {
    return null;
  }
  const cents = Math.round(numeric * 100);
  return Number.isSafeInteger(cents) ? cents : null;
}

export function formatMinor(minor: number | null, currency: string): string {
  if (minor === null || !Number.isSafeInteger(minor)) {
    return "non disponibile";
  }
  const negative = minor < 0;
  const absolute = Math.abs(minor);
  const whole = Math.trunc(absolute / 100).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  const fraction = String(absolute % 100).padStart(2, "0");
  return `${negative ? "-" : ""}${whole},${fraction} ${currency}`;
}

export function formatSignedMinor(minor: number | null, currency: string): string {
  if (minor === null) {
    return "non disponibile";
  }
  const text = formatMinor(Math.abs(minor), currency);
  if (text === "non disponibile") {
    return text;
  }
  if (minor > 0) {
    return `+${text}`;
  }
  if (minor < 0) {
    return `-${text}`;
  }
  return text;
}

export function formatBps(bps: number | null): string {
  if (bps === null || !Number.isSafeInteger(bps)) {
    return "non disponibile";
  }
  const absolute = Math.abs(bps);
  const text = `${Math.trunc(absolute / 100)},${String(absolute % 100).padStart(2, "0")}%`;
  if (bps > 0) {
    return `+${text}`;
  }
  if (bps < 0) {
    return `-${text}`;
  }
  return text;
}

export function parseBenchmarkId(body: string, symbol: string): { instrumentId: string; name: string | null } | null {
  const items = arrayOf(body, "items");
  if (!items) {
    return null;
  }
  for (const item of items) {
    if (item.internalSymbolFull !== symbol || item.isHiddenFromClient === true) {
      continue;
    }
    const instrumentId = positiveId(item.instrumentId);
    const internal = positiveId(item.internalInstrumentId);
    if (!instrumentId || (internal !== null && internal !== instrumentId)) {
      continue;
    }
    const name = text(item.internalInstrumentDisplayName);
    return { instrumentId, name };
  }
  return null;
}

export function parseEurUsdRate(body: string): string | null {
  const rows = arrayOf(body, "balances");
  if (!rows) {
    return null;
  }
  for (const row of rows) {
    if (row.currency !== "EUR" || row.displayCurrency !== "USD") {
      continue;
    }
    const rate = numberOrNull(typeof row.exchangeRate === "number" || typeof row.exchangeRate === "string" ? String(row.exchangeRate) : null);
    if (rate !== null && rate > 0) {
      return String(row.exchangeRate);
    }
  }
  return null;
}

export function parseReferenceClose(body: string, openAt: string | null): string | null {
  const open = openAt ? Date.parse(openAt) : Number.NaN;
  if (!Number.isFinite(open)) {
    return null;
  }
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    return null;
  }
  if (!isRecord(root) || (root.interval !== undefined && root.interval !== "FiveMinutes") || !Array.isArray(root.candles)) {
    return null;
  }
  let bestTime = Number.NEGATIVE_INFINITY;
  let bestClose: string | null = null;
  for (const group of root.candles) {
    if (!isRecord(group) || !Array.isArray(group.candles)) {
      continue;
    }
    for (const row of group.candles) {
      if (!isRecord(row) || typeof row.fromDate !== "string") {
        continue;
      }
      const start = Date.parse(row.fromDate);
      const close = decimalText(row.close);
      if (!Number.isFinite(start) || close === null) {
        continue;
      }
      const end = start + 300_000;
      if (end <= open && start >= bestTime) {
        bestTime = start;
        bestClose = close;
      }
    }
  }
  return bestClose;
}

type RawPosition = {
  orderId: string;
  instrumentId: string;
  symbol: string;
  units: string | null;
  openRate: string | null;
  openAt: string | null;
  costUsdMinor: number | null;
};

export function selectProgramPositions(body: string): RawPosition[] | null {
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    return null;
  }
  if (!isRecord(root) || !isRecord(root.clientPortfolio)) {
    return null;
  }
  const rows = root.clientPortfolio.positions;
  if (rows == null) {
    return [];
  }
  if (!Array.isArray(rows)) {
    return null;
  }
  const known = new Map<string, (typeof PROGRAM_ORDERS)[number]>(PROGRAM_ORDERS.map((order) => [order.orderId, order]));
  const selected: RawPosition[] = [];
  for (const item of rows) {
    if (!isRecord(item)) {
      continue;
    }
    const orderId = positiveId(item.orderID);
    const instrumentId = positiveId(item.instrumentID);
    if (!orderId || !instrumentId) {
      continue;
    }
    const order = known.get(orderId);
    if (!order || order.instrumentId !== instrumentId) {
      continue;
    }
    selected.push({
      orderId,
      instrumentId,
      symbol: order.symbol,
      units: decimalText(item.units),
      openRate: decimalText(item.openRate),
      openAt: typeof item.openDateTime === "string" && !Number.isNaN(Date.parse(item.openDateTime)) ? item.openDateTime : null,
      costUsdMinor: moneyMinor(item.initialAmountInDollars),
    });
  }
  return selected;
}

export function buildPosition(
  row: RawPosition,
  bid: string | null,
  quoteTime: string | null,
  name: string | null,
  eurUsdRate: string | null,
): ProgramPosition {
  const valueUsdMinor = productMinor(row.units, bid);
  const changeUsdMinor = valueUsdMinor !== null && row.costUsdMinor !== null ? valueUsdMinor - row.costUsdMinor : null;
  return {
    orderId: row.orderId,
    instrumentId: row.instrumentId,
    symbol: row.symbol,
    name,
    units: row.units,
    openRate: row.openRate,
    bid,
    quoteTime,
    sinceOpen: tick(row.openRate, bid),
    valueUsdMinor,
    costUsdMinor: row.costUsdMinor,
    changeUsdMinor,
    valueEurMinor: valueUsdMinor === null ? null : usdToEurMinor(valueUsdMinor, eurUsdRate),
    changeEurMinor: changeUsdMinor === null ? null : usdToEurMinor(changeUsdMinor, eurUsdRate),
  };
}

export function summarize(positions: ProgramPosition[] | null, eurUsdRate: string | null): Pick<ProgramBook, "valueUsdMinor" | "valueEurMinor" | "changeUsdMinor" | "changeEurMinor" | "lossEurMinor" | "lossBpsOfBudget"> {
  if (!positions) {
    return { valueUsdMinor: null, valueEurMinor: null, changeUsdMinor: null, changeEurMinor: null, lossEurMinor: null, lossBpsOfBudget: null };
  }
  if (positions.length === 0) {
    return { valueUsdMinor: 0, valueEurMinor: 0, changeUsdMinor: 0, changeEurMinor: 0, lossEurMinor: 0, lossBpsOfBudget: 0 };
  }
  const values = positions.map((position) => position.valueUsdMinor);
  const changes = positions.map((position) => position.changeUsdMinor);
  const valueUsdMinor = values.every((value) => value !== null) ? values.reduce<number>((sum, value) => sum + (value ?? 0), 0) : null;
  const changeUsdMinor = changes.every((value) => value !== null) ? changes.reduce<number>((sum, value) => sum + (value ?? 0), 0) : null;
  const valueEurMinor = valueUsdMinor === null ? null : usdToEurMinor(valueUsdMinor, eurUsdRate);
  const changeEurMinor = changeUsdMinor === null ? null : usdToEurMinor(changeUsdMinor, eurUsdRate);
  const lossEurMinor = changeEurMinor === null ? null : Math.max(0, -changeEurMinor);
  const lossBpsOfBudget = lossEurMinor === null ? null : Math.floor((lossEurMinor * 10_000) / BUDGET_EUR_MINOR);
  return { valueUsdMinor, valueEurMinor, changeUsdMinor, changeEurMinor, lossEurMinor, lossBpsOfBudget };
}

export function missingBenchmark(): BenchmarkQuote {
  return {
    resolved: false,
    instrumentId: null,
    name: null,
    bid: null,
    quoteTime: null,
    referencePrice: null,
    sinceReference: null,
    changeBps: null,
  };
}

function positiveId(value: unknown): string | null {
  if (typeof value !== "number" || !Number.isInteger(value) || value <= 0 || value > 2147483647) {
    return null;
  }
  return String(value);
}

function text(value: unknown): string | null {
  if (typeof value !== "string") {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

export function decimalText(value: unknown): string | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return Object.is(value, -0) ? "0" : String(value);
  }
  if (typeof value === "string" && /^\d+(\.\d+)?$/.test(value.trim())) {
    return value.trim();
  }
  return null;
}

function numberOrNull(value: string | null): number | null {
  if (value === null) {
    return null;
  }
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

function arrayOf(body: string, field: string): Record<string, unknown>[] | null {
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    return null;
  }
  if (!isRecord(root) || !Array.isArray(root[field])) {
    return null;
  }
  return root[field].filter(isRecord);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
