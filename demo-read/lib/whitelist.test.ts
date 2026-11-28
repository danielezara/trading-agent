import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  WHITELIST,
  clearWhitelistCache,
  eurPair,
  mergeCards,
  parseCandles,
  portfolioNote,
  readWhitelist,
  type CryptoCard,
} from "./whitelist.ts";

const keys = {
  ETORO_DEMO_API_KEY: "test-api",
  ETORO_DEMO_USER_KEY: "test-user",
};

describe("whitelist", () => {
  it("lists the twenty tickers in the requested order", () => {
    assert.deepEqual(WHITELIST, [
      "BTC", "ETH", "BNB", "XRP", "SOL", "TRX", "HYPE", "ZEC", "DOGE", "LEO",
      "XMR", "LINK", "ADA", "XLM", "BCH", "NEAR", "LTC", "CC", "AVAX", "SUI",
    ]);
  });

  it("uses the catalog euro id and ignores the bare ticker", () => {
    const found = eurPair("BTC", page([
      cryptoRow("BTC", 100000),
      cryptoRow("BTCEUR", 100109),
    ]));
    assert.deepEqual(found, { kind: "pair", pair: { symbol: "BTCEUR", instrumentId: "100109" } });
  });

  it("writes that the euro pair is absent instead of using another quote", () => {
    const found = eurPair("BNB", page([
      cryptoRow("BNB", 100030),
      { internalAssetClassName: "Stocks", internalSymbolFull: "BNB.BR", instrumentId: 1, internalInstrumentId: 1 },
    ]));
    assert.deepEqual(found, { kind: "absent", reason: "Coppia EUR assente" });
    const stocks = eurPair("CC", page([
      { internalAssetClassName: "Stocks", internalSymbolFull: "CC.US", instrumentId: 9, internalInstrumentId: 9 },
    ]));
    assert.deepEqual(stocks, { kind: "absent", reason: "Coppia EUR assente" });
  });

  it("does not pick a pair from an incomplete catalog page", () => {
    const found = eurPair("BTC", page([cryptoRow("BTCEUR", 100109)], 51));
    assert.deepEqual(found, { kind: "absent", reason: "Catalogo incompleto" });
  });

  it("does not choose when two euro ids exist", () => {
    const found = eurPair("BTC", page([
      cryptoRow("BTCEUR", 100109),
      cryptoRow("BTCEUR", 100110),
    ]));
    assert.deepEqual(found, { kind: "absent", reason: "Coppia ambigua" });
  });

  it("keeps closed bars and the in-progress five-minute candle", () => {
    const now = Date.parse("2026-11-28T06:06:00Z");
    const candles = parseCandles(JSON.stringify({
      interval: "FiveMinutes",
      candles: [{
        candles: [
          { fromDate: "2026-11-28T06:05:00Z", open: 3, high: 4, low: 2, close: 3.5 },
          { fromDate: "2026-11-28T06:00:00Z", open: 1, high: 2, low: 0.5, close: 1.5 },
          { fromDate: "2026-11-28T05:55:00Z", open: 1, high: 0.5, low: 0.4, close: 0.9 },
          { fromDate: "2026-11-28T06:10:00Z", open: 4, high: 5, low: 3.5, close: 4.2 },
        ],
      }],
    }), now);
    assert.deepEqual(candles, [
      { start: "2026-11-28T06:00:00Z", open: 1, high: 2, low: 0.5, close: 1.5, forming: false },
      { start: "2026-11-28T06:05:00Z", open: 3, high: 4, low: 2, close: 3.5, forming: true },
    ]);
  });

  it("reads candle prices when eToro sends them as strings", () => {
    const now = Date.parse("2026-11-28T06:06:00Z");
    const candles = parseCandles(JSON.stringify({
      interval: "FiveMinutes",
      candles: [{
        candles: [
          { fromDate: "2026-11-28T06:00:00Z", open: "1", high: "2", low: "0.5", close: "1.5" },
        ],
      }],
    }), now);
    assert.deepEqual(candles, [
      { start: "2026-11-28T06:00:00Z", open: 1, high: 2, low: 0.5, close: 1.5, forming: false },
    ]);
  });

  it("says the return is unavailable when the crypto is not in the program book", () => {
    assert.deepEqual(portfolioNote({ symbol: "BTCEUR", instrumentId: "100109" }, []), {
      membership: "Non nel portafoglio",
      performance: "non disponibile",
    });
    assert.deepEqual(portfolioNote(null, []), {
      membership: "Non verificabile",
      performance: "non disponibile",
    });
    assert.deepEqual(portfolioNote({ symbol: "BTCEUR", instrumentId: "100109" }, null), {
      membership: "Portafoglio non disponibile",
      performance: "non disponibile",
    });
    assert.equal(portfolioNote(
      { symbol: "BTCEUR", instrumentId: "100109" },
      [{ instrumentId: "100109", openRate: "100", bid: "110" }],
    ).performance, "+10,00%");
    assert.equal(portfolioNote(
      { symbol: "BTCEUR", instrumentId: "100109" },
      [{ instrumentId: "100109", openRate: null, bid: null }],
    ).performance, "non disponibile");
  });

  it("refreshes several cryptos on their own and keeps candles after a failed reread", async () => {
    clearWhitelistCache();
    const calls: string[] = [];
    let now = new Date("2026-11-28T06:06:00Z");
    let failCandles = false;
    const fetchImpl = async (url: string, init: { method: "GET" }) => {
      assert.equal(init.method, "GET");
      assert.equal(url.includes("/real"), false);
      assert.equal(url.includes("BTCEUR"), false);
      calls.push(url);
      const asset = new URL(url).searchParams.get("internalSymbolFull");
      if (asset === "BTC") {
        return json(page([cryptoRow("BTC", 100000), cryptoRow("BTCEUR", 100109)]));
      }
      if (asset) {
        return json(page([cryptoRow(asset, 1000)]));
      }
      if (url.endsWith("/100109/history/candles/desc/FiveMinutes/30")) {
        if (failCandles) {
          return { status: 429, headers: { get: () => "30" }, text: async () => "" };
        }
        return json(JSON.stringify({
          interval: "FiveMinutes",
          candles: [{ candles: [{ fromDate: "2026-11-28T06:00:00Z", open: 1, high: 2, low: 0.5, close: 1.5 }] }],
        }));
      }
      return json("");
    };
    const cards = await readWhitelist({ env: keys, now: () => now, fetchImpl });
    const afterFirst = calls.length;
    assert.equal(cards.length, 20);
    assert.equal(cards[0].pair?.instrumentId, "100109");
    assert.equal(cards[0].price, "1.5");
    assert.equal(cards[0].candles.length, 1);
    assert.equal(cards[0].stale, false);
    assert.equal(cards[1].reason, "Coppia EUR assente");
    assert.equal(afterFirst >= 2, true);
    assert.equal(calls.some((url) => url.includes("/trading/execution")), false);

    const btcCandlesBefore = calls.filter((url) => url.includes("/100109/history/candles/")).length;
    const again = await readWhitelist({ env: keys, now: () => now, fetchImpl });
    assert.equal(again[0].price, "1.5");
    assert.equal(calls.filter((url) => url.includes("/100109/history/candles/")).length, btcCandlesBefore);

    now = new Date("2026-11-28T06:07:00Z");
    failCandles = true;
    const held = await readWhitelist({ env: keys, now: () => now, fetchImpl });
    assert.equal(held[0].price, "1.5");
    assert.equal(held[0].candles.length, 1);
    assert.equal(held[0].stale, true);
    assert.equal(held[0].reason, "Ultimo disponibile");
    clearWhitelistCache();
  });

  it("updates other cryptos when one candle read fails", async () => {
    clearWhitelistCache();
    const now = new Date("2026-11-28T06:06:00Z");
    const fetchImpl = async (url: string) => {
      const asset = new URL(url).searchParams.get("internalSymbolFull");
      if (asset === "BTC") {
        return json(page([cryptoRow("BTCEUR", 100109)]));
      }
      if (asset === "ETH") {
        return json(page([cryptoRow("ETHEUR", 100110)]));
      }
      if (asset) {
        return json(page([cryptoRow(asset, 1000)]));
      }
      if (url.includes("/100109/history/candles/")) {
        return { status: 503, headers: { get: () => null }, text: async () => "" };
      }
      if (url.includes("/100110/history/candles/")) {
        return json(JSON.stringify({
          interval: "FiveMinutes",
          candles: [{ candles: [{ fromDate: "2026-11-28T06:00:00Z", open: 2, high: 3, low: 1, close: 2.5 }] }],
        }));
      }
      return json("");
    };
    const cards = await readWhitelist({ env: keys, now: () => now, fetchImpl });
    assert.equal(cards[0].pair?.instrumentId, "100109");
    assert.equal(cards[0].candles.length, 0);
    assert.equal(cards[1].pair?.instrumentId, "100110");
    assert.equal(cards[1].price, "2.5");
    assert.equal(cards[1].candles.length, 1);
    clearWhitelistCache();
  });

  it("keeps updating other cryptos when one catalog search fails", async () => {
    clearWhitelistCache();
    const now = new Date("2026-11-28T06:06:00Z");
    const fetchImpl = async (url: string) => {
      const asset = new URL(url).searchParams.get("internalSymbolFull");
      if (asset === "BTC") {
        return json(page([cryptoRow("BTCEUR", 100109)]));
      }
      if (asset === "ETH") {
        return { status: 503, headers: { get: () => null }, text: async () => "" };
      }
      if (asset === "BNB") {
        return json(page([cryptoRow("BNBEUR", 100111)]));
      }
      if (asset) {
        return json(page([cryptoRow(asset, 1000)]));
      }
      if (url.includes("/history/candles/")) {
        const id = url.match(/instruments\/(\d+)\//)?.[1];
        return json(JSON.stringify({
          interval: "FiveMinutes",
          candles: [{ candles: [{ fromDate: "2026-11-28T06:00:00Z", open: 1, high: id === "100111" ? 10 : 2, low: 0.5, close: id === "100111" ? 9 : 1.5 }] }],
        }));
      }
      return json("");
    };
    const cards = await readWhitelist({ env: keys, now: () => now, fetchImpl });
    assert.equal(cards[0].price, "1.5");
    assert.equal(cards[1].reason, "Ricerca non disponibile");
    assert.equal(cards[2].pair?.instrumentId, "100111");
    assert.equal(cards[2].price, "9");
    clearWhitelistCache();
  });

  it("refreshes candles again after the catalog ttl without blocking other pairs", async () => {
    clearWhitelistCache();
    let now = new Date("2026-11-28T06:06:00Z");
    let candleCalls = 0;
    const fetchImpl = async (url: string) => {
      const asset = new URL(url).searchParams.get("internalSymbolFull");
      if (asset === "BTC") {
        return json(page([cryptoRow("BTCEUR", 100109)]));
      }
      if (asset) {
        return json(page([cryptoRow(`${asset}EUR`, 100200 + asset.length)]));
      }
      if (url.includes("/history/candles/")) {
        candleCalls += 1;
        return json(JSON.stringify({
          interval: "FiveMinutes",
          candles: [{ candles: [{ fromDate: "2026-11-28T06:00:00Z", open: 1, high: 2, low: 0.5, close: 1.5 }] }],
        }));
      }
      return json("");
    };
    await readWhitelist({ env: keys, now: () => now, fetchImpl });
    const afterFirst = candleCalls;
    now = new Date("2026-11-28T06:16:30Z");
    await readWhitelist({ env: keys, now: () => now, fetchImpl });
    assert.equal(candleCalls > afterFirst, true);
    clearWhitelistCache();
  });

  it("keeps a drawn chart when a later payload has no candles", () => {
    const previous: CryptoCard[] = [{
      ticker: "BTC",
      pair: { symbol: "BTCEUR", instrumentId: "100109" },
      reason: null,
      candles: [{ start: "2026-11-28T06:00:00Z", open: 1, high: 2, low: 0.5, close: 1.5, forming: false }],
      price: "1.5",
      stale: false,
      quotedAt: "2026-11-28T06:06:00Z",
    }];
    const incoming: CryptoCard[] = WHITELIST.map((ticker) => ({
      ticker,
      pair: null,
      reason: ticker === "BTC" ? "Ricerca non disponibile" : "Lettura in corso",
      candles: [],
      price: null,
      stale: false,
      quotedAt: null,
    }));
    const merged = mergeCards(previous, incoming);
    assert.equal(merged[0].price, "1.5");
    assert.equal(merged[0].candles.length, 1);
    assert.equal(merged[0].reason, "Ultimo disponibile");
    assert.equal(merged[1].reason, "Lettura in corso");
  });

  it("does not repeat a rate-limited catalog read on the next poll", async () => {
    clearWhitelistCache();
    const searched = new Map<string, number>();
    const now = new Date("2026-11-28T06:06:00Z");
    const fetchImpl = async (url: string) => {
      const asset = new URL(url).searchParams.get("internalSymbolFull") ?? url;
      searched.set(asset, (searched.get(asset) ?? 0) + 1);
      return { status: 429, headers: { get: () => "30" }, text: async () => "" };
    };
    const first = await readWhitelist({ env: keys, now: () => now, fetchImpl });
    const btcFirst = searched.get("BTC") ?? 0;
    const second = await readWhitelist({ env: keys, now: () => now, fetchImpl });
    assert.equal(first[0].reason, "Ricerca non disponibile");
    assert.equal(second[0].reason, "Ricerca non disponibile");
    assert.equal(btcFirst, 1);
    assert.equal(searched.get("BTC"), 1);
    clearWhitelistCache();
  });

  it("does not call the network when a key is missing", async () => {
    let called = false;
    const cards = await readWhitelist({
      env: { ETORO_DEMO_API_KEY: "present" },
      fetchImpl: async () => {
        called = true;
        throw new Error("no network");
      },
    });
    assert.equal(called, false);
    assert.equal(cards.length, 20);
    assert.equal(cards.every((card) => card.reason === "Chiavi non disponibili" && card.price === null && card.pair === null), true);
  });
});

function cryptoRow(symbol: string, id: number) {
  return {
    internalAssetClassName: "Crypto",
    internalSymbolFull: symbol,
    instrumentId: id,
    internalInstrumentId: id,
  };
}

function page(items: unknown[], total = items.length): string {
  return JSON.stringify({ totalItems: total, items });
}

function json(body: string) {
  return {
    status: 200,
    headers: { get: () => null },
    text: async () => body,
  };
}
