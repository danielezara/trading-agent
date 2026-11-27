export const DEMO_PORTFOLIO_URL = "https://public-api.etoro.com/api/v1/trading/info/demo/portfolio";
export const DEMO_ACCOUNT_FALLBACK = "Conto Demo delle chiavi in backend/.env";

const LABEL_KEYS = ["username", "userName", "accountId", "account_id"] as const;

export type BrokerCash = {
  minorUnits: number;
  currency: string;
};

export type DemoOverview = {
  platform: "Demo eToro";
  operationalState: "OBSERVATION";
  tradingEnabled: false;
  connected: boolean;
  accountLabel: string | null;
  brokerCash: BrokerCash | null;
  lastProbeAt: string | null;
  probeErrorClass: string | null;
};

type FetchLike = (input: string, init: { method: "GET"; headers: Record<string, string>; redirect: "manual"; signal: AbortSignal }) => Promise<{
  status: number;
  headers: { get(name: string): string | null };
  text(): Promise<string>;
}>;

type ReadOptions = {
  env: Record<string, string | undefined>;
  fetchImpl: FetchLike;
  now?: () => Date;
  sleep?: (ms: number) => Promise<void>;
};

export async function readDemoOverview(options: ReadOptions): Promise<DemoOverview> {
  const now = options.now ?? (() => new Date());
  const apiKey = options.env.ETORO_DEMO_API_KEY?.trim() ?? "";
  const userKey = options.env.ETORO_DEMO_USER_KEY?.trim() ?? "";
  if (!apiKey || !userKey) {
    return view(false, null, null, now(), "KEYS_MISSING");
  }
  const outcome = await readOnce(options.fetchImpl, apiKey, userKey, options.sleep ?? defaultSleep);
  if (outcome.kind === "pass") {
    return view(true, outcome.accountLabel, outcome.brokerCash, now(), null);
  }
  return view(false, null, null, now(), outcome.errorClass);
}

function view(
  connected: boolean,
  accountLabel: string | null,
  brokerCash: BrokerCash | null,
  at: Date,
  probeErrorClass: string | null,
): DemoOverview {
  return {
    platform: "Demo eToro",
    operationalState: "OBSERVATION",
    tradingEnabled: false,
    connected,
    accountLabel,
    brokerCash,
    lastProbeAt: at.toISOString(),
    probeErrorClass,
  };
}

async function readOnce(
  fetchImpl: FetchLike,
  apiKey: string,
  userKey: string,
  sleep: (ms: number) => Promise<void>,
): Promise<{ kind: "pass"; accountLabel: string | null; brokerCash: BrokerCash | null } | { kind: "closed"; errorClass: string }> {
  const first = await get(fetchImpl, apiKey, userKey);
  if (first.kind === "transport") {
    return { kind: "closed", errorClass: "TRANSPORT" };
  }
  const response = first.status === 429 && retryDelay(first.retryAfter) !== null
    ? await retry(fetchImpl, apiKey, userKey, sleep, retryDelay(first.retryAfter)!)
    : first;
  if (response.kind === "transport") {
    return { kind: "closed", errorClass: "TRANSPORT" };
  }
  if (response.status === 429) {
    return { kind: "closed", errorClass: "RATE_LIMITED" };
  }
  return classify(response.status, response.body);
}

async function retry(
  fetchImpl: FetchLike,
  apiKey: string,
  userKey: string,
  sleep: (ms: number) => Promise<void>,
  delayMs: number,
) {
  await sleep(delayMs);
  return get(fetchImpl, apiKey, userKey);
}

async function get(fetchImpl: FetchLike, apiKey: string, userKey: string) {
  try {
    const response = await fetchImpl(DEMO_PORTFOLIO_URL, {
      method: "GET",
      redirect: "manual",
      signal: AbortSignal.timeout(5000),
      headers: {
        Accept: "application/json",
        "x-request-id": crypto.randomUUID(),
        "x-api-key": apiKey,
        "x-user-key": userKey,
      },
    });
    if (response.status >= 300 && response.status < 400) {
      return { kind: "transport" as const };
    }
    const body = await response.text();
    return {
      kind: "http" as const,
      status: response.status,
      retryAfter: response.headers.get("retry-after"),
      body: body.length > 2_000_000 ? "" : body,
    };
  } catch {
    return { kind: "transport" as const };
  }
}

function retryDelay(header: string | null): number | null {
  const seconds = Number(header?.trim());
  if (!Number.isInteger(seconds) || seconds < 0 || seconds > 2) {
    return null;
  }
  return seconds * 1000;
}

function classify(status: number, body: string) {
  if (status === 200) {
    const parsed = parsePortfolio(body);
    return parsed ? { kind: "pass" as const, ...parsed } : { kind: "closed" as const, errorClass: "RESPONSE_INVALID" };
  }
  if (status === 401 || status === 403) {
    return { kind: "closed" as const, errorClass: "AUTHENTICATION" };
  }
  if (status >= 400 && status < 500) {
    return { kind: "closed" as const, errorClass: "CLIENT_ERROR" };
  }
  if (status >= 500 && status < 600) {
    return { kind: "closed" as const, errorClass: "SERVER_ERROR" };
  }
  return { kind: "closed" as const, errorClass: "TRANSPORT" };
}

export function parsePortfolio(body: string): { accountLabel: string | null; brokerCash: BrokerCash | null } | null {
  let root: unknown;
  try {
    root = JSON.parse(body);
  } catch {
    return null;
  }
  if (!isRecord(root)) {
    return null;
  }
  const portfolio = root.clientPortfolio;
  if (!isRecord(portfolio)) {
    return null;
  }
  return {
    accountLabel: accountLabel(root, portfolio),
    brokerCash: brokerCash(portfolio),
  };
}

function brokerCash(portfolio: Record<string, unknown>): BrokerCash | null {
  const currency = currencyCode(portfolio.accountCurrencyId);
  const minorUnits = minor(portfolio.credit);
  if (!currency || minorUnits === null || minorUnits < 0) {
    return null;
  }
  return { minorUnits, currency };
}

function currencyCode(value: unknown): string | null {
  if (value === undefined || value === null) {
    return "USD";
  }
  if (value === 1) {
    return "USD";
  }
  return null;
}

function minor(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return null;
  }
  const cents = Math.round(value * 100);
  if (!Number.isSafeInteger(cents)) {
    return null;
  }
  return cents;
}

function accountLabel(root: Record<string, unknown>, portfolio: Record<string, unknown>): string | null {
  for (const key of LABEL_KEYS) {
    for (const node of [portfolio, root]) {
      const value = node[key];
      const text = typeof value === "string" ? value.trim() : typeof value === "number" && Number.isInteger(value) ? String(value) : "";
      if (isPublicAccountLabel(text)) {
        return text;
      }
    }
  }
  return null;
}

function isPublicAccountLabel(text: string): boolean {
  return text.length >= 1 && text.length <= 64 && /^[A-Za-z0-9 ._\-@]+$/.test(text);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

export function formatCash(cash: BrokerCash | null): string {
  if (!cash) {
    return "non disponibile";
  }
  const absolute = Math.abs(cash.minorUnits);
  const whole = Math.trunc(absolute / 100).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  const fraction = String(absolute % 100).padStart(2, "0");
  return `${cash.minorUnits < 0 ? "-" : ""}${whole},${fraction} ${cash.currency}`;
}

export function displayAccount(label: string | null): string {
  return label && label.trim() ? label : DEMO_ACCOUNT_FALLBACK;
}
