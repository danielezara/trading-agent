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

  it("keeps only closed five-minute candles", () => {
    const now = Date.parse("2026-11-28T06:06:00Z");
    const candles = parseCandles(JSON.stringify({
      interval: "FiveMinutes",
      candles: [{
        candles: [
          { fromDate: "2026-11-28T06:05:00Z", open: 3, high: 4, low: 2, close: 3.5 },
          { fromDate: "2026-11-28T06:00:00Z", open: 1, high: 2, low: 0.5, close: 1.5 },
          { fromDate: "2026-11-28T05:55:00Z", open: 1, high: 0.5, low: 0.4, close: 0.9 },
        ],
      }],
    }), now);
    assert.deepEqual(candles, [
      { start: "2026-11-28T06:00:00Z", open: 1, high: 2, low: 0.5, close: 1.5 },
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

  it("reads one euro pair per window and keeps its candles when the next read fails", async () => {
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
    assert.equal(cards.length, 20);
    assert.equal(cards[0].pair?.instrumentId, "100109");
    assert.equal(cards[0].price, "1.5");
    assert.equal(cards[0].candles.length, 1);
    assert.equal(cards[0].stale, false);
    assert.equal(cards[1].reason, "Lettura in corso");
    assert.equal(calls.length, 2);
    assert.equal(calls.some((url) => url.includes("/trading/execution")), false);

    const again = await readWhitelist({ env: keys, now: () => now, fetchImpl });
    assert.equal(again[0].price, "1.5");
    assert.equal(calls.length, 2);

    now = new Date("2026-11-28T06:07:00Z");
    failCandles = true;
    const held = await readWhitelist({ env: keys, now: () => now, fetchImpl });
    assert.equal(held[0].price, "1.5");
    assert.equal(held[0].candles.length, 1);
    assert.equal(held[0].stale, true);
    assert.equal(held[0].reason, "Ultimo disponibile");
    clearWhitelistCache();
  });

  it("keeps a drawn chart when a later payload has no candles", () => {
    const previous: CryptoCard[] = [{
      ticker: "BTC",
      pair: { symbol: "BTCEUR", instrumentId: "100109" },
      reason: null,
      candles: [{ start: "2026-11-28T06:00:00Z", open: 1, high: 2, low: 0.5, close: 1.5 }],
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

  it("does not repeat a failed catalog read on the next poll", async () => {
    clearWhitelistCache();
    let calls = 0;
    const now = new Date("2026-11-28T06:06:00Z");
    const fetchImpl = async () => {
      calls += 1;
      return { status: 429, headers: { get: () => "30" }, text: async () => "" };
    };
    const first = await readWhitelist({ env: keys, now: () => now, fetchImpl });
    const second = await readWhitelist({ env: keys, now: () => now, fetchImpl });
    assert.equal(first[0].reason, "Ricerca non disponibile");
    assert.equal(second[0].reason, "Ricerca non disponibile");
    assert.equal(first[1].reason, "Lettura in corso");
    assert.equal(calls, 1);
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
