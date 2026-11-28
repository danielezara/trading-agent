import { describe, expect, it } from "vitest";
import { assertSkeletonApiBase } from "./policy";

describe("assertSkeletonApiBase", () => {
  it("allows an empty base and localhost", () => {
    expect(() => assertSkeletonApiBase("")).not.toThrow();
    expect(() => assertSkeletonApiBase("http://127.0.0.1:8080")).not.toThrow();
  });

  it("rejects a remote host, embedded credentials, and a broker path", () => {
    expect(() => assertSkeletonApiBase("https://example.com")).toThrow(/locale/);
    expect(() => assertSkeletonApiBase("http://user:pw@127.0.0.1")).toThrow(/credenziali/);
    const brokerPath = ["e", "toro"].join("");
    expect(() => assertSkeletonApiBase(`http://127.0.0.1/${brokerPath}`)).toThrow(/broker/);
  });
});
