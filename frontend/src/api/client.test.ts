import { getJson } from "./client";

describe("getJson", () => {
  it("rejects anything other than a local read path", async () => {
    await expect(getJson("https://example.com/api/v1/system/status")).rejects.toThrow(/relativo/);
    await expect(getJson("/orders")).rejects.toThrow(/consentito/);
    await expect(getJson("/api/../orders")).rejects.toThrow(/relativo/);
  });

  it("reads json only with GET", async () => {
    const seen: Array<{ path: string; method: string }> = [];
    vi.stubGlobal("fetch", (path: string, init?: { method?: string }) => {
      seen.push({ path, method: init?.method ?? "" });
      return Promise.resolve({
        ok: true,
        status: 200,
        json: async () => ({ ok: true }),
      });
    });
    await expect(getJson("/api/v1/operational-state")).resolves.toEqual({ ok: true });
    expect(seen).toEqual([{ path: "/api/v1/operational-state", method: "GET" }]);
    vi.unstubAllGlobals();
  });
});
