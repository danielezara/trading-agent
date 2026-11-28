import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { DEMO_ACCOUNT_FALLBACK, DEMO_PORTFOLIO_URL, formatCash, readDemoOverview, readProgramBook } from "./demo-read.ts";
import { changeBps, formatBps, missingValueSentence, parseBenchmarkId, parseReferenceClose, selectProgramPositions, tick } from "./program-book.ts";

const keys = {
  ETORO_DEMO_API_KEY: "test-api",
  ETORO_DEMO_USER_KEY: "test-user",
};

describe("demo read", () => {
  it("does not call the network when a key is missing", async () => {
    let called = false;
    const view = await readDemoOverview({
      env: { ETORO_DEMO_API_KEY: "present" },
      fetchImpl: async () => {
        called = true;
        throw new Error("no network");
      },
      now: () => new Date("2026-11-27T08:00:00Z"),
    });
    assert.equal(called, false);
    assert.equal(view.connected, false);
    assert.equal(view.probeErrorClass, "KEYS_MISSING");
    assert.equal(view.accountLabel, null);
    assert.equal(view.brokerCash, null);
    assert.equal(view.positions, null);
    assert.equal(view.operationalState, "OBSERVATION");
    assert.equal(view.tradingEnabled, false);
  });

  it("reads cash and a public label from a demo portfolio", async () => {
    const calls: Array<{ url: string; method: string; cache: string; headers: Record<string, string> }> = [];
    const view = await readDemoOverview({
      env: keys,
      now: () => new Date("2026-11-27T08:00:00Z"),
      fetchImpl: async (url, init) => {
        calls.push({ url, method: init.method, cache: init.cache, headers: init.headers });
        return json(200, {
          clientPortfolio: { credit: 280.35, accountCurrencyId: 1, username: "demo.user", positions: [] },
        });
      },
    });
    assert.equal(calls[0]?.url, DEMO_PORTFOLIO_URL);
    assert.equal(calls.some((call) => call.url.includes("/real")), false);
    assert.equal(calls[0]?.method, "GET");
    assert.equal(calls[0]?.cache, "no-store");
    assert.equal(calls[0]?.url.includes("/real/"), false);
    assert.equal(view.connected, true);
    assert.equal(view.accountLabel, "demo.user");
    assert.deepEqual(view.brokerCash, { minorUnits: 28035, currency: "USD" });
    assert.equal(formatCash(view.brokerCash), "280,35 USD");
    assert.deepEqual(view.positions, []);
    assert.equal(view.probeErrorClass, null);
  });

  it("keeps the fallback when the read has no public account label", async () => {
    const view = await readDemoOverview({
      env: keys,
      fetchImpl: async () => json(200, { clientPortfolio: { credit: 10, username: "a".repeat(80) } }),
    });
    assert.equal(view.connected, true);
    assert.equal(view.accountLabel, null);
    assert.equal(DEMO_ACCOUNT_FALLBACK, "Conto Demo delle chiavi in backend/.env");
  });

  it("records authentication failure and does not follow a long rate limit", async () => {
    const denied = await readDemoOverview({
      env: keys,
      fetchImpl: async () => json(401, { message: "nope" }),
    });
    assert.equal(denied.connected, false);
    assert.equal(denied.probeErrorClass, "AUTHENTICATION");
    assert.equal(denied.positions, null);

    let calls = 0;
    const limited = await readDemoOverview({
      env: keys,
      sleep: async () => {
        throw new Error("must not wait");
      },
      fetchImpl: async () => {
        calls += 1;
        return {
          status: 429,
          headers: { get: (name) => (name === "retry-after" ? "30" : null) },
          text: async () => "",
        };
      },
    });
    assert.equal(calls, 1);
    assert.equal(limited.probeErrorClass, "RATE_LIMITED");
  });

  it("lists direct positions and ignores copies and rows without an instrument", async () => {
    const view = await readDemoOverview({
      env: keys,
      fetchImpl: async () => json(200, {
        clientPortfolio: {
          credit: 280.35,
          accountCurrencyId: 1,
          positions: [
            { instrumentID: 1002, units: 0.049485, amount: 100, CID: 7765437 },
            { units: 1, amount: 5 },
            "skip",
            { instrumentID: 1003, amount: 12.5 },
          ],
          mirrors: [{ positions: [{ instrumentID: 9, units: 1, amount: 50 }] }],
        },
      }),
    });
    assert.equal(view.connected, true);
    assert.equal(view.tradingEnabled, false);
    assert.deepEqual(view.positions, [
      { instrumentId: "1002", quantity: "0.049485", value: { minorUnits: 10000, currency: "USD" }, displayName: null, quotePrice: null, quoteTime: null },
      { instrumentId: "1003", quantity: null, value: { minorUnits: 1250, currency: "USD" }, displayName: null, quotePrice: null, quoteTime: null },
    ]);
  });

  it("attaches the official name and bid or last, and leaves a missing quote blank", async () => {
    const calls: string[] = [];
    const view = await readDemoOverview({
      env: keys,
      fetchImpl: async (url) => {
        calls.push(url);
        if (url.startsWith("https://public-api.etoro.com/api/v1/market-data/instruments/rates")) {
          return json(200, {
            rates: [
              { instrumentID: 1002, bid: 1.25, lastExecution: 1.1, date: "2026-11-27T09:30:00Z" },
              { instrumentID: 1003, lastExecution: 2.5, date: "2026-11-27T09:31:00Z" },
            ],
          });
        }
        if (url === "https://public-api.etoro.com/api/v1/market-data/instruments?instrumentIds=1002,1003" || url.startsWith("https://public-api.etoro.com/api/v1/market-data/instruments?")) {
          return json(200, {
            instrumentDisplayDatas: [
              { instrumentID: 1002, instrumentDisplayName: "Apple", symbolFull: "AAPL" },
              { instrumentID: 1003, instrumentDisplayName: "  ", symbolFull: "MSFT" },
            ],
          });
        }
        return json(200, {
          clientPortfolio: {
            credit: 10,
            accountCurrencyId: 1,
            positions: [
              { instrumentID: 1002, units: 1, amount: 1 },
              { instrumentID: 1003, units: 2, amount: 2 },
            ],
          },
        });
      },
    });
    assert.equal(view.operationalState, "OBSERVATION");
    assert.equal(view.tradingEnabled, false);
    assert.equal(view.positions?.[0]?.displayName, "Apple");
    assert.equal(view.positions?.[0]?.quotePrice, "1.25");
    assert.equal(view.positions?.[0]?.quoteTime, "2026-11-27T09:30:00Z");
    assert.equal(view.positions?.[1]?.displayName, null);
    assert.equal(view.positions?.[1]?.quotePrice, "2.5");
    assert.equal(view.positions?.[1]?.quoteTime, "2026-11-27T09:31:00Z");
    assert.equal(calls.some((url) => url.includes("/feeds") || url.includes("news") || url.includes("/real/")), false);
    assert.equal(calls.filter((url) => url.includes("/market-data/instruments/rates")).length, 1);
    assert.equal(calls.filter((url) => url.startsWith("https://public-api.etoro.com/api/v1/market-data/instruments?")).length, 1);
  });

  it("reads the portfolio again when the page asks again", async () => {
    let calls = 0;
    const fetchImpl: Parameters<typeof readDemoOverview>[0]["fetchImpl"] = async (url) => {
      if (url === DEMO_PORTFOLIO_URL) {
        calls += 1;
      }
      return json(200, {
        clientPortfolio: {
          credit: 10,
          accountCurrencyId: 1,
          positions: Array.from({ length: calls }, (_, index) => ({
            instrumentID: index + 1,
            units: 1,
            amount: 1,
          })),
        },
      });
    };
    const first = await readDemoOverview({ env: keys, fetchImpl });
    const second = await readDemoOverview({ env: keys, fetchImpl });
    assert.equal(first.positions?.length, 1);
    assert.equal(second.positions?.length, 2);
    assert.equal(calls, 2);
  });

  it("ships no browser secret prefix and no order method", () => {
    const page = readFileSync(new URL("../app/page.tsx", import.meta.url), "utf8");
    const bot = readFileSync(new URL("../app/bot-portfolio.tsx", import.meta.url), "utf8");
    const rules = readFileSync(new URL("../app/regole/page.tsx", import.meta.url), "utf8");
    const route = readFileSync(new URL("../app/api/overview/route.ts", import.meta.url), "utf8");
    const botRoute = readFileSync(new URL("../app/api/bot-portfolio/route.ts", import.meta.url), "utf8");
    const source = `${page}\n${bot}\n${rules}\n${route}\n${botRoute}\n${readFileSync(new URL("./demo-read.ts", import.meta.url), "utf8")}\n${readFileSync(new URL("./program-book.ts", import.meta.url), "utf8")}`;
    assert.equal(source.includes("VITE_"), false);
    assert.equal(source.includes("NEXT_PUBLIC_"), false);
    assert.equal(source.includes("ETORO_DEMO_API_KEY="), false);
    assert.equal(/\bPOST\b/.test(route), false);
    assert.equal(/\bPOST\b/.test(botRoute), false);
    assert.equal(source.includes("market-open-orders"), false);
    assert.match(route, /export async function GET/);
    assert.match(botRoute, /export async function GET/);
    assert.equal(page.includes("use client"), false);
    assert.equal(page.includes("fetchCache = \"force-no-store\""), true);
    assert.equal(route.includes("fetchCache = \"force-no-store\""), true);
    assert.equal(bot.includes("Portafoglio bot"), true);
    assert.equal(bot.includes("Nessuna posizione del programma"), true);
    assert.equal(bot.includes("Confronto non disponibile"), true);
    assert.equal(page.includes("Posizioni Demo"), false);
    assert.equal(rules.includes("due chiusure al rialzo"), true);
    assert.equal(rules.includes("closed-bar-trend/1"), true);
    assert.equal(rules.includes("non ha caricato la strategia di breakout"), true);
    assert.equal(rules.includes("due chiusure al ribasso"), true);
    assert.equal(rules.includes("2×ATR"), true);
    assert.equal(rules.includes("390 barre"), true);
    assert.equal(rules.includes("500 EUR"), true);
    assert.equal(rules.includes("2,50 EUR"), true);
    assert.equal(rules.includes("75 EUR"), true);
    assert.equal(rules.includes("8 posizioni"), true);
    assert.equal(rules.includes("50 EUR"), true);
    assert.equal(rules.includes("solo long"), true);
    assert.equal(rules.includes("leva 1"), true);
    assert.equal(rules.includes("Solo Bitcoin, solo long, leva 1."), true);
    assert.equal(rules.includes("simbolo BTC"), true);
    assert.equal(rules.includes("senza quel campo"), true);
    assert.equal(rules.includes("azioni e ETF"), false);
    assert.equal(rules.includes("15 secondi"), true);
    assert.equal(rules.includes("non invia vendite"), true);
    assert.equal(rules.includes("use client"), false);
    assert.equal(page.includes("Storico operazioni vuoto"), true);
    assert.equal(page.includes("Registro movimenti"), true);
  });

  it("shows an empty operation history and a native cash movement", async () => {
    const view = await readDemoOverview({
      env: keys,
      now: () => new Date("2026-11-27T10:30:00Z"),
      fetchImpl: async (url) => {
        if (url.includes("/real")) {
          throw new Error("real path");
        }
        if (url.startsWith("https://public-api.etoro.com/api/v1/balances")) {
          return json(200, {
            balances: [
              { accountType: "Cash", accountId: "f0995efc-25a1-465e-bec3-d309aaf00ede", currency: "EUR" },
              { accountType: "Trading", accountId: "12345678", currency: "USD" },
            ],
          });
        }
        if (url.includes("/money/accounts/cash/") && url.includes("/transactions")) {
          assert.equal(url.includes("12345678"), false);
          return json(200, {
            results: [
              {
                id: "m1",
                direction: "credit",
                status: "settled",
                transactionType: "internalTransfer",
                amount: "100.00",
                currency: "EUR",
                postedAt: "2026-05-03T10:16:12Z",
                originalAmount: "90",
                originalCurrency: "USD",
                conversionRate: "1.1111",
              },
            ],
          });
        }
        if (url.startsWith("https://public-api.etoro.com/api/v1/trading/info/trade/demo/history")) {
          return json(200, []);
        }
        return json(200, { clientPortfolio: { credit: 10, accountCurrencyId: 1, positions: [] } });
      },
    });
    assert.equal(view.tradingEnabled, false);
    assert.equal(view.operationalState, "OBSERVATION");
    assert.deepEqual(view.closedTrades, []);
    assert.deepEqual(view.cashMovements, [
      {
        direction: "credit",
        status: "settled",
        kind: "internalTransfer",
        minorUnits: 10000,
        currency: "EUR",
        postedAt: "2026-05-03T10:16:12Z",
      },
    ]);
  });

  it("keeps only the program order and leaves a missing quote or benchmark blank", async () => {
    const calls: string[] = [];
    const book = await readProgramBook({
      env: keys,
      now: () => new Date("2026-11-27T16:00:00Z"),
      fetchImpl: async (url, init) => {
        calls.push(url);
        assert.equal(init.method, "GET");
        assert.equal(url.includes("/real"), false);
        assert.equal(url.includes("market-open"), false);
        if (url === DEMO_PORTFOLIO_URL) {
          return json(200, {
            clientPortfolio: {
              credit: 10,
              accountCurrencyId: 1,
              positions: [
                { instrumentID: 1005, orderID: 9, units: 1, amount: 50, initialAmountInDollars: 50, openRate: 10, openDateTime: "2021-03-09T15:18:38.233Z" },
                { instrumentID: 1001, orderID: 397030666, units: 0.229735, amount: 82.08, initialAmountInDollars: 82.08, openRate: 357.27, openDateTime: "2026-11-27T15:13:27.72Z" },
              ],
              ordersForOpen: [
                { instrumentID: 1002, orderID: 397065869, amount: 82.01, openDateTime: "2026-11-27T20:55:47.463Z" },
                { instrumentID: 1137, orderID: 880000001, amount: 82, openDateTime: "2026-11-27T21:05:15.697Z" },
              ],
            },
          });
        }
        if (url.startsWith("https://public-api.etoro.com/api/v1/balances")) {
          return json(200, { balances: [{ currency: "EUR", displayCurrency: "USD", exchangeRate: 1.09352 }] });
        }
        if (url.includes("internalSymbolFull=SWDA.L")) {
          return json(200, { items: [{ internalSymbolFull: "SWDA.L", instrumentId: 3040, internalInstrumentId: 3040, isHiddenFromClient: false, internalInstrumentDisplayName: "iShares Core MSCI World UCITS ETF" }] });
        }
        if (url.startsWith("https://public-api.etoro.com/api/v1/market-data/instruments/rates")) {
          assert.equal(url.includes("1005"), false);
          assert.equal(url.includes("1001"), true);
          assert.equal(url.includes("1002"), true);
          assert.equal(url.includes("1137"), true);
          assert.equal(url.includes("3040"), true);
          return json(200, {
            rates: [
              { instrumentID: 1001, bid: 358.12, date: "2026-11-27T15:59:00Z" },
              { instrumentID: 1002, bid: 320.5, date: "2026-11-27T15:59:00Z" },
              { instrumentID: 1137, bid: 180, date: "2026-11-27T15:59:00Z" },
              { instrumentID: 3040, bid: 110.5, date: "2026-11-27T15:59:00Z" },
            ],
          });
        }
        if (url.includes("/history/candles/")) {
          assert.equal(url.includes("/3040/"), true);
          return json(200, { interval: "FiveMinutes", candles: [{ candles: [{ fromDate: "2026-11-27T15:05:00Z", close: 110 }] }] });
        }
        return json(200, {
          instrumentDisplayDatas: [
            { instrumentID: 1001, instrumentDisplayName: "Apple", symbolFull: "AAPL" },
            { instrumentID: 1002, instrumentDisplayName: "Alphabet", symbolFull: "GOOG" },
            { instrumentID: 1137, instrumentDisplayName: "NVIDIA Corporation", symbolFull: "NVDA" },
          ],
        });
      },
    });
    assert.equal(book.connected, true);
    assert.equal(book.positions?.length, 3);
    assert.equal(book.positions?.[0]?.symbol, "AAPL");
    assert.equal(book.positions?.[0]?.orderId, "397030666");
    assert.equal(book.positions?.[0]?.sinceOpen, "up");
    assert.equal(book.positions?.[0]?.bid, "358.12");
    assert.equal(book.positions?.[1]?.symbol, "GOOG");
    assert.equal(book.positions?.[1]?.orderId, "397065869");
    assert.equal(book.positions?.[1]?.bid, "320.5");
    assert.equal(book.positions?.[1]?.valueEurMinor, null);
    assert.equal(book.positions?.[2]?.symbol, "NVDA");
    assert.equal(book.positions?.[2]?.orderId, "880000001");
    assert.equal(book.valueEurMinor, 7523);
    assert.deepEqual(book.missingValues, ["GOOG", "NVDA"]);
    assert.equal(missingValueSentence(book.missingValues), "Manca il valore di GOOG e NVDA");
    assert.equal(book.benchmark.resolved, true);
    assert.equal(book.benchmark.instrumentId, "3040");
    assert.equal(book.benchmark.referencePrice, "110");
    assert.equal(book.benchmark.sinceReference, "up");
    assert.equal(calls.some((url) => url.includes("1005")), false);

    const hidden = parseBenchmarkId(JSON.stringify({ items: [{ internalSymbolFull: "SWDA.L", instrumentId: 3040, internalInstrumentId: 9, isHiddenFromClient: false }] }), "SWDA.L");
    assert.equal(hidden, null);
    assert.equal(selectProgramPositions(JSON.stringify({ clientPortfolio: { positions: [{ instrumentID: 1005, orderID: 9, openDateTime: "2021-03-09T15:18:38.233Z" }] } }))?.length, 0);
    assert.equal(parseReferenceClose(JSON.stringify({ interval: "FiveMinutes", candles: [] }), null), null);
    assert.equal(tick("10", null), null);
    assert.equal(changeBps("110", "110.5"), 45);
    assert.equal(formatBps(null), "non disponibile");
  });
});

function json(status: number, body: unknown) {
  return {
    status,
    headers: { get: () => null },
    text: async () => JSON.stringify(body),
  };
}
