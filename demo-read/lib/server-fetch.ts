import { request as httpsRequest } from "node:https";

type ResponseLike = {
  status: number;
  headers: { get(name: string): string | null };
  text(): Promise<string>;
};

/** GET that Next does not cache into the page. The raw eToro body stays on the server. */
export function serverFetch(input: string, init: { method: "GET"; headers: Record<string, string>; signal: AbortSignal }): Promise<ResponseLike> {
  const url = new URL(input);
  return new Promise((resolve, reject) => {
    const req = httpsRequest(
      {
        protocol: url.protocol,
        hostname: url.hostname,
        path: `${url.pathname}${url.search}`,
        method: "GET",
        headers: init.headers,
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on("data", (chunk: Buffer) => {
          chunks.push(chunk);
        });
        res.on("end", () => {
          const headers = res.headers;
          resolve({
            status: res.statusCode ?? 0,
            headers: {
              get(name: string) {
                const value = headers[name.toLowerCase()];
                if (Array.isArray(value)) {
                  return value[0] ?? null;
                }
                return value ?? null;
              },
            },
            text: async () => Buffer.concat(chunks).toString("utf8"),
          });
        });
      },
    );
    const abort = () => {
      req.destroy();
      reject(new Error("aborted"));
    };
    if (init.signal.aborted) {
      abort();
      return;
    }
    init.signal.addEventListener("abort", abort, { once: true });
    req.on("error", reject);
    req.end();
  });
}
