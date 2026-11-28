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
  forming: boolean;
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
  stale: boolean;
  quotedAt: string | null;
};

const SEARCH_URL = "https://public-api.etoro.com/api/v1/market-data/search";
const CANDLE_COUNT = 30;

type FetchLike = (input: string, init: { method: "GET"; headers: Record<string, string>; redirect: "manual"; signal: AbortSignal; cache: "no-store" }) => Promise<{
  status: number;
  headers: { get(name: string): string | null };
  text(): Promise<string>;
}>;

const CATALOG_TTL_MS = 10 * 60 * 1000;
const CANDLE_TTL_MS = 20 * 1000;
const FAIL_COOLDOWN_MS = 30 * 1000;
const RATE_LIMIT_MS = 60 * 1000;
const MAX_PARALLEL = 6;

type Slot = {
  card: CryptoCard;
  catalogAt: number;
  candleAt: number | null;
  pauseUntil: number;
};

const slots = new Map<string, Slot>();

export function clearWhitelistCache(): void {
  slots.clear();
}

export function mergeCards(previous: CryptoCard[] | null, incoming: CryptoCard[]): CryptoCard[] {
  const prior = new Map((previous ?? []).map((card) => [card.ticker, card]));
  return WHITELIST.map((ticker) => {
    const next = incoming.find((card) => card.ticker === ticker) ?? pendingCard(ticker);
    const old = prior.get(ticker);
    if (old && old.candles.length > 0 && next.candles.length === 0) {
      return { ...old, stale: true, reason: "Ultimo disponibile" };
    }
    if (old && next.reason === "Lettura in corso" && old.reason !== "Lettura in corso") {
      return old;
    }
    return next;
  });
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
  const due = dueTickers(at).slice(0, MAX_PARALLEL);
  await Promise.all(due.map((ticker) => refreshTicker(ticker, at, options.fetchImpl, apiKey, userKey, sleep)));
  return snapshot();
}

async function refreshTicker(
  ticker: string,
  at: number,
  fetchImpl: FetchLike,
  apiKey: string,
  userKey: string,
  sleep: (ms: number) => Promise<void>,
): Promise<void> {
  const slot = slots.get(ticker);
  if (!catalogFresh(slot, at)) {
    const search = await readBody(fetchImpl, searchUrl(ticker), apiKey, userKey, sleep);
    if (search.body === null) {
      if (slot?.card.candles.length && slot.card.price) {
        slot.card = { ...slot.card, stale: true, reason: "Ultimo disponibile" };
        slot.catalogAt = at;
        slot.pauseUntil = at + (search.rateLimited ? RATE_LIMIT_MS : FAIL_COOLDOWN_MS);
      } else {
        slots.set(ticker, {
          card: emptyCard(ticker, "Ricerca non disponibile"),
          catalogAt: at,
          candleAt: null,
          pauseUntil: at + (search.rateLimited ? RATE_LIMIT_MS : FAIL_COOLDOWN_MS),
        });
      }
      return;
    }
    const found = eurPair(ticker, search.body);
    const previous = slots.get(ticker);
    if (found.kind === "absent") {
      if (previous?.card.candles.length) {
        previous.card = { ...previous.card, stale: true, reason: "Ultimo disponibile" };
        previous.catalogAt = at;
        previous.pauseUntil = 0;
      } else {
        slots.set(ticker, {
          card: emptyCard(ticker, found.reason),
          catalogAt: at,
          candleAt: null,
          pauseUntil: 0,
        });
      }
      return;
    }
    slots.set(ticker, {
      card: {
        ticker,
        pair: found.pair,
        reason: previous?.card.candles.length ? null : "Candele non disponibili",
        candles: previous?.card.candles ?? [],
        price: previous?.card.price ?? null,
        stale: Boolean(previous?.card.candles.length) ? previous!.card.stale : false,
        quotedAt: previous?.card.quotedAt ?? null,
      },
      catalogAt: at,
      candleAt: previous?.candleAt ?? null,
      pauseUntil: 0,
    });
  }
  const ready = slots.get(ticker);
  if (!ready?.card.pair || !candlesDue(ready, at)) {
    return;
  }
  const read = await readBody(fetchImpl, candleUrl(ready.card.pair.instrumentId), apiKey, userKey, sleep);
  const candles = read.body === null ? null : parseCandles(read.body, at);
  ready.candleAt = at;
  if (candles && candles.length > 0) {
    ready.card = {
      ticker,
      pair: ready.card.pair,
      reason: null,
      candles,
      price: String(candles[candles.length - 1].close),
      stale: false,
      quotedAt: new Date(at).toISOString(),
    };
    ready.pauseUntil = 0;
    return;
  }
  if (ready.card.candles.length > 0 && ready.card.price) {
    ready.card = { ...ready.card, stale: true, reason: "Ultimo disponibile" };
  } else {
    ready.card = {
      ...ready.card,
      candles: [],
      price: null,
      stale: false,
      reason: candles === null ? "Candele non disponibili" : "Nessuna candela",
    };
  }
  ready.pauseUntil = at + (read.rateLimited ? RATE_LIMIT_MS : FAIL_COOLDOWN_MS);
}

function snapshot(): CryptoCard[] {
  return WHITELIST.map((ticker) => slots.get(ticker)?.card ?? pendingCard(ticker));
}

function dueTickers(at: number): string[] {
  const catalog: { ticker: string; age: number }[] = [];
  const candles: { ticker: string; age: number }[] = [];
  for (const ticker of WHITELIST) {
    const slot = slots.get(ticker);
    if (slot && at < slot.pauseUntil) {
      continue;
    }
    const age = slot?.candleAt ?? slot?.catalogAt ?? Number.NEGATIVE_INFINITY;
    if (!catalogFresh(slot, at)) {
      catalog.push({ ticker, age });
      continue;
    }
    if (slot?.card.pair && candlesDue(slot, at)) {
      candles.push({ ticker, age });
    }
  }
  catalog.sort((left, right) => left.age - right.age);
  candles.sort((left, right) => left.age - right.age);
  // Alternate so catalog bootstrap and live candle refresh stay independent.
  const mixed: string[] = [];
  let catalogIndex = 0;
  let candleIndex = 0;
  while (catalogIndex < catalog.length || candleIndex < candles.length) {
    if (catalogIndex < catalog.length) {
      mixed.push(catalog[catalogIndex++].ticker);
    }
    if (candleIndex < candles.length) {
      mixed.push(candles[candleIndex++].ticker);
    }
  }
  return mixed;
}

function candlesDue(slot: Slot, at: number): boolean {
  return slot.candleAt === null || at - slot.candleAt >= CANDLE_TTL_MS;
}

function catalogFresh(slot: Slot | undefined, at: number): boolean {
  if (!slot) {
    return false;
  }
  const ttl = slot.card.reason === "Ricerca non disponibile" || slot.card.reason === "Catalogo incompleto"
    ? FAIL_COOLDOWN_MS
    : CATALOG_TTL_MS;
  return at - slot.catalogAt < ttl;
}

function pendingCard(ticker: string): CryptoCard {
  return emptyCard(ticker, "Lettura in corso");
}

function emptyCard(ticker: string, reason: string): CryptoCard {
  return { ticker, pair: null, reason, candles: [], price: null, stale: false, quotedAt: null };
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
  // Keep the in-progress five-minute bar so the chart can move before the close.
  if (!Number.isFinite(start) || start > nowMs) {
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
  return {
    start: row.fromDate,
    open,
    high,
    low,
    close,
    forming: start + 300_000 > nowMs,
  };
}

function finite(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return value;
  }
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  }
  return null;
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
): Promise<{ body: string | null; rateLimited: boolean }> {
  const first = await get(fetchImpl, url, apiKey, userKey);
  if (first.kind === "transport") {
    return { body: null, rateLimited: false };
  }
  const rateLimited = first.status === 429;
  const retryAfter = rateLimited ? retryDelay(first.retryAfter) : null;
  const response = retryAfter === null ? first : await retry(fetchImpl, url, apiKey, userKey, sleep, retryAfter);
  if (response.kind === "transport" || response.status !== 200 || !response.body.trim()) {
    return { body: null, rateLimited: rateLimited || (response.kind === "http" && response.status === 429) };
  }
  return { body: response.body, rateLimited: false };
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
