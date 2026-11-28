import { changeBps, formatBps } from "./program-book.ts";

export const WHITELIST = [
  "BTC",
  "ETH",
  "BNB",
  "XRP",
  "SOL",
  "TRX",
  "HYPE",
  "ZEC",
  "DOGE",
  "LEO",
  "XMR",
  "LINK",
  "ADA",
  "XLM",
  "BCH",
  "NEAR",
  "LTC",
  "CC",
  "AVAX",
  "SUI",
] as const;

export type Candle = {
  start: string;
  open: number;
  high: number;
  low: number;
  close: number;
};

export type EuroPair = {
  symbol: string;
  instrumentId: string;
};

export type PairRead =
  | { kind: "pair"; pair: EuroPair }
  | { kind: "absent"; reason: string };

export type CryptoCard = {
  ticker: string;
  pair: EuroPair | null;
  reason: string | null;
  candles: Candle[];
  price: string | null;
};

const SEARCH_URL = "https://public-api.etoro.com/api/v1/market-data/search";
const CANDLE_COUNT = 30;

type FetchLike = (input: string, init: { method: "GET"; headers: Record<string, string>; redirect: "manual"; signal: AbortSignal; cache: "no-store" }) => Promise<{
  status: number;
  headers: { get(name: string): string | null };
  text(): Promise<string>;
}>;

const PAIR_TTL_MS = 60_000;
const MISS_TTL_MS = 15_000;
const pairCache = new Map<string, { at: number; found: PairRead }>();

export function clearWhitelistCache(): void {
  pairCache.clear();
}

export function portfolioNote(
  pair: EuroPair | null,
  positions: { instrumentId: string; openRate: string | null; bid: string | null }[] | null,
): { membership: string; performance: string } {
  if (positions === null) {
    return { membership: "Portafoglio non disponibile", performance: "non disponibile" };
  }
  if (pair === null) {
    return { membership: "Non verificabile", performance: "non disponibile" };
  }
  const held = positions.find((position) => position.instrumentId === pair.instrumentId);
  if (!held) {
    return { membership: "Non nel portafoglio", performance: "non disponibile" };
  }
  return { membership: "Nel portafoglio", performance: formatBps(changeBps(held.openRate, held.bid)) };
}

export function eurPair(asset: string, body: string): PairRead {
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    return { kind: "absent", reason: "Ricerca non disponibile" };
  }
  if (!isRecord(root) || !Array.isArray(root.items) || typeof root.totalItems !== "number") {
    return { kind: "absent", reason: "Ricerca non disponibile" };
  }
  if (root.totalItems !== root.items.length) {
    return { kind: "absent", reason: "Catalogo incompleto" };
  }
  const pairs: EuroPair[] = [];
  for (const item of root.items) {
    if (!isRecord(item) || item.internalAssetClassName !== "Crypto") {
      continue;
    }
    const symbol = typeof item.internalSymbolFull === "string" ? item.internalSymbolFull.trim() : "";
    if (!symbol || symbol.includes(".") || quoteSuffix(asset, symbol) !== "EUR") {
      continue;
    }
    const instrumentId = positiveId(item.instrumentId);
    const internal = positiveId(item.internalInstrumentId);
    if (!instrumentId || (internal !== null && internal !== instrumentId)) {
      continue;
    }
    pairs.push({ symbol, instrumentId });
  }
  const ids = [...new Set(pairs.map((pair) => pair.instrumentId))];
  if (ids.length > 1) {
    return { kind: "absent", reason: "Coppia ambigua" };
  }
  if (ids.length === 0) {
    return { kind: "absent", reason: "Coppia EUR assente" };
  }
  return { kind: "pair", pair: pairs[0] };
}

export function parseCandles(body: string, nowMs: number): Candle[] | null {
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    return null;
  }
  if (!isRecord(root) || (root.interval !== undefined && root.interval !== "FiveMinutes") || !Array.isArray(root.candles)) {
    return null;
  }
  const bars: Candle[] = [];
  for (const group of root.candles) {
    if (!isRecord(group) || !Array.isArray(group.candles)) {
      continue;
    }
    for (const row of group.candles) {
      const candle = closedCandle(row, nowMs);
      if (candle) {
        bars.push(candle);
      }
    }
  }
  bars.sort((left, right) => left.start.localeCompare(right.start));
  const unique: Candle[] = [];
  for (const bar of bars) {
    const previous = unique[unique.length - 1];
    if (previous && previous.start === bar.start) {
      if (previous.open !== bar.open || previous.high !== bar.high || previous.low !== bar.low || previous.close !== bar.close) {
        return null;
      }
      continue;
    }
    unique.push(bar);
  }
  return unique;
}

export async function readWhitelist(options: {
  env: Record<string, string | undefined>;
  fetchImpl: FetchLike;
  now?: () => Date;
  sleep?: (ms: number) => Promise<void>;
}): Promise<CryptoCard[]> {
  const now = options.now ?? (() => new Date());
  const apiKey = options.env.ETORO_DEMO_API_KEY?.trim() ?? "";
  const userKey = options.env.ETORO_DEMO_USER_KEY?.trim() ?? "";
  if (!apiKey || !userKey) {
    return WHITELIST.map((ticker) => emptyCard(ticker, "Chiavi non disponibili"));
  }
  const sleep = options.sleep ?? (async () => {});
  const at = now().getTime();
  return mapLimited(WHITELIST, 4, async (ticker) => {
    const cached = pairCache.get(ticker);
    const ttl = cached?.found.kind === "absent" && cached.found.reason === "Ricerca non disponibile" ? MISS_TTL_MS : PAIR_TTL_MS;
    let found = cached && at - cached.at < ttl ? cached.found : null;
    if (found === null) {
      const search = await readBody(options.fetchImpl, searchUrl(ticker), apiKey, userKey, sleep);
      if (search === null) {
        const unavailable = { kind: "absent" as const, reason: "Ricerca non disponibile" };
        pairCache.set(ticker, { at, found: unavailable });
        return emptyCard(ticker, unavailable.reason);
      }
      found = eurPair(ticker, search);
      pairCache.set(ticker, { at, found });
    }
    if (found.kind === "absent") {
      return emptyCard(ticker, found.reason);
    }
    const body = await readBody(options.fetchImpl, candleUrl(found.pair.instrumentId), apiKey, userKey, sleep);
    const candles = body === null ? null : parseCandles(body, at);
    const last = candles && candles.length > 0 ? candles[candles.length - 1] : null;
    return {
      ticker,
      pair: found.pair,
      reason: candles === null ? "Candele non disponibili" : candles.length === 0 ? "Nessuna candela chiusa" : null,
      candles: candles ?? [],
      price: last ? String(last.close) : null,
    };
  });
}

function emptyCard(ticker: string, reason: string): CryptoCard {
  return { ticker, pair: null, reason, candles: [], price: null };
}

function searchUrl(asset: string): string {
  if (!/^[A-Z0-9]{1,16}$/.test(asset)) {
    throw new Error("whitelist asset is not usable");
  }
  return `${SEARCH_URL}?internalSymbolFull=${asset}&pageSize=100`;
}

function candleUrl(id: string): string {
  if (!/^[1-9]\d{0,9}$/.test(id)) {
    throw new Error("instrument id is not a positive integer");
  }
  return `https://public-api.etoro.com/api/v1/market-data/instruments/${id}/history/candles/desc/FiveMinutes/${CANDLE_COUNT}`;
}

function quoteSuffix(asset: string, symbol: string): string | null {
  if (!symbol.startsWith(asset)) {
    return null;
  }
  const rest = symbol.slice(asset.length);
  if (rest.length === 0) {
    return "";
  }
  if (rest.length < 3 || rest.length > 4 || /[^A-Za-z]/.test(rest)) {
    return null;
  }
  return rest;
}

function closedCandle(row: unknown, nowMs: number): Candle | null {
  if (!isRecord(row) || typeof row.fromDate !== "string") {
    return null;
  }
  const start = Date.parse(row.fromDate);
  if (!Number.isFinite(start) || start + 300_000 > nowMs) {
    return null;
  }
  const open = finite(row.open);
  const high = finite(row.high);
  const low = finite(row.low);
  const close = finite(row.close);
  if (open === null || high === null || low === null || close === null) {
    return null;
  }
  if (low < 0 || low > open || low > close || high < open || high < close) {
    return null;
  }
  return { start: row.fromDate, open, high, low, close };
}

function finite(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : null;
}

function positiveId(value: unknown): string | null {
  if (typeof value !== "number" || !Number.isInteger(value) || value <= 0) {
    return null;
  }
  return String(value);
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
  const retryAfter = first.status === 429 ? retryDelay(first.retryAfter) : null;
  const response = retryAfter === null ? first : await retry(fetchImpl, url, apiKey, userKey, sleep, retryAfter);
  if (response.kind === "transport" || response.status !== 200 || !response.body.trim()) {
    return null;
  }
  return response.body;
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
      signal: AbortSignal.timeout(8000),
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
    if (url.includes("/real") || url.includes("/feeds") || url.includes("market-open")) {
      return { kind: "transport" as const };
    }
    return {
      kind: "http" as const,
      status: response.status,
      body: await response.text(),
      retryAfter: response.headers.get("retry-after"),
    };
  } catch {
    return { kind: "transport" as const };
  }
}

function retryDelay(header: string | null): number | null {
  if (!header) {
    return null;
  }
  const seconds = Number(header);
  if (!Number.isInteger(seconds) || seconds < 0 || seconds > 2) {
    return null;
  }
  return seconds * 1000;
}

async function mapLimited<T, R>(items: readonly T[], limit: number, task: (item: T) => Promise<R>): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  async function worker() {
    while (next < items.length) {
      const index = next;
      next += 1;
      results[index] = await task(items[index]);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
  return results;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
