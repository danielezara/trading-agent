import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { DEMO_ACCOUNT_FALLBACK, DEMO_PORTFOLIO_URL, formatCash, readDemoOverview } from "./demo-read.ts";

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
    assert.equal(calls.length, 1);
    assert.equal(calls[0]?.url, DEMO_PORTFOLIO_URL);
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
      { instrumentId: "1002", quantity: "0.049485", value: { minorUnits: 10000, currency: "USD" } },
      { instrumentId: "1003", quantity: null, value: { minorUnits: 1250, currency: "USD" } },
    ]);
  });

  it("reads the portfolio again when the page asks again", async () => {
    let calls = 0;
    const fetchImpl: Parameters<typeof readDemoOverview>[0]["fetchImpl"] = async () => {
      calls += 1;
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
    const route = readFileSync(new URL("../app/api/overview/route.ts", import.meta.url), "utf8");
    const source = `${page}\n${route}\n${readFileSync(new URL("./demo-read.ts", import.meta.url), "utf8")}`;
    assert.equal(source.includes("VITE_"), false);
    assert.equal(source.includes("NEXT_PUBLIC_"), false);
    assert.equal(/\bPOST\b/.test(route), false);
    assert.match(route, /export async function GET/);
    assert.equal(page.includes("use client"), false);
    assert.equal(page.includes("fetchCache = \"force-no-store\""), true);
    assert.equal(route.includes("fetchCache = \"force-no-store\""), true);
    assert.equal(page.includes("Posizioni Demo"), true);
    assert.equal(page.includes("Nessuna posizione"), true);
  });
});

function json(status: number, body: unknown) {
  return {
    status,
    headers: { get: () => null },
    text: async () => JSON.stringify(body),
  };
}
