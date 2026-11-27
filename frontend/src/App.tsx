import { useEffect, useState, type FormEvent } from "react";
import { NavLink, Route, Routes } from "react-router-dom";
import { getJson } from "./api/client";
import { assertSkeletonApiBase } from "./api/policy";
import type { AuditChain, ChatTurn, ClosedTrade, Configuration, DataChannel, DataHealth, Decision, Envelope, Money, OperationalState, Overview } from "./api/types";
import "./styles.css";

const NAV = [
  { to: "/", label: "Panoramica", end: true },
  { to: "/portafoglio", label: "Portafoglio", end: false },
  { to: "/performance", label: "Performance", end: false },
  { to: "/attivita", label: "Attività", end: false },
  { to: "/dati", label: "Dati e agente", end: false },
  { to: "/alert", label: "Alert", end: false },
  { to: "/audit", label: "Audit", end: false },
  { to: "/chat", label: "Chat", end: false },
  { to: "/configurazione", label: "Configurazione", end: false },
];

type DashboardData = {
  overview: Overview;
  configuration: Configuration;
  operational: OperationalState;
  decision: Decision;
  health: DataHealth;
  audit: AuditChain;
};

type LoadState =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; data: DashboardData };

function Shell() {
  const apiBase = import.meta.env.VITE_API_BASE_URL ?? "";
  try {
    assertSkeletonApiBase(apiBase);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Configurazione non valida";
    return (
      <main id="contenuto">
        <section className="panel">
          <h1>Configurazione bloccata</h1>
          <p>{message}</p>
        </section>
      </main>
    );
  }

  return <DashboardShell />;
}

function DashboardShell() {
  const loaded = useDashboardData();
  const stateLabel = loaded.status === "ready" ? loaded.data.operational.state : loaded.status === "loading" ? "caricamento" : "non disponibile";

  return (
    <>
      <a className="skip-link" href="#contenuto">Salta al contenuto</a>
      <header className="app-header">
        <div>
          <p className="product-name">Agente trading</p>
          <p className="product-note">ambiente Demo</p>
        </div>
        <div className="badge-row">
          <span className="badge badge-demo">DEMO</span>
          <span className="badge badge-state" data-testid="operational-state">{stateLabel}</span>
        </div>
      </header>
      <nav className="nav" aria-label="Principale">
        {NAV.map((item) => (
          <NavLink key={item.to} to={item.to} end={item.end}>
            {item.label}
          </NavLink>
        ))}
      </nav>
      <main id="contenuto">
        <Routes>
          <Route path="/" element={<OverviewPage loaded={loaded} />} />
          <Route path="/portafoglio" element={<PortfolioPage loaded={loaded} />} />
          <Route path="/performance" element={<Page title="Performance" />} />
          <Route path="/attivita" element={<Page title="Attività" />} />
          <Route path="/dati" element={<DataPage loaded={loaded} />} />
          <Route path="/alert" element={<Page title="Alert" />} />
          <Route path="/audit" element={<AuditPage loaded={loaded} />} />
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/configurazione" element={<ConfigurationPage loaded={loaded} />} />
        </Routes>
      </main>
    </>
  );
}

function useDashboardData(): LoadState {
  const [loaded, setLoaded] = useState<LoadState>({ status: "loading" });
  useEffect(() => {
    let cancelled = false;
    Promise.all([
      getJson<Envelope<Overview>>("/api/dashboard/v1/overview"),
      getJson<Envelope<Configuration>>("/api/v1/configurations/active"),
      getJson<Envelope<OperationalState>>("/api/v1/operational-state"),
      getJson<Envelope<Decision>>("/api/v1/decisions/latest"),
      getJson<Envelope<DataHealth>>("/api/v1/data-health"),
      getJson<Envelope<AuditChain>>("/api/v1/audit"),
    ]).then(([overview, configuration, operational, decision, health, audit]) => {
      if (!cancelled) {
        setLoaded({
          status: "ready",
          data: {
            overview: overview.data,
            configuration: configuration.data,
            operational: operational.data,
            decision: decision.data,
            health: health.data,
            audit: audit.data,
          },
        });
      }
    }).catch(() => {
      if (!cancelled) {
        setLoaded({ status: "error", message: "API locale non raggiungibile. Nessun dato è stato inventato." });
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return loaded;
}

const DEMO_ACCOUNT_FALLBACK = "Conto Demo delle chiavi in backend/.env";

function PositionsTable({ positions }: { positions: Overview["positions"] | undefined }) {
  if (positions == null) {
    return <p data-testid="positions">Posizioni non disponibili</p>;
  }
  if (positions.length === 0) {
    return <p data-testid="positions">Nessuna posizione</p>;
  }
  return (
    <table className="data-table" data-testid="positions">
      <caption>Posizioni Demo</caption>
      <thead>
        <tr>
          <th>Strumento</th>
          <th>Nome</th>
          <th>Quantità</th>
          <th>Valore</th>
          <th>Quotazione</th>
          <th>Ora</th>
          <th>Tipo</th>
          <th>Allowlist</th>
        </tr>
      </thead>
      <tbody>
        {positions.map((position, index) => (
          <tr key={`${position.instrument_id}-${index}`}>
            <td>{position.instrument_id}</td>
            <td data-testid="instrument-name">{position.display_name ?? ""}</td>
            <td>{position.quantity ?? "non disponibile"}</td>
            <td>{cashAmount(position.value)}</td>
            <td data-testid="quote-price">{position.quote_price ?? ""}</td>
            <td data-testid="quote-time">{position.quote_time ?? ""}</td>
            <td data-testid="asset-class">{assetClassLabel(position.asset_class)}</td>
            <td data-testid="allowlist">{allowlistLabel(position.allowlist)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function assetClassLabel(value: string | null | undefined): string {
  if (value === "stock") {
    return "azione";
  }
  if (value === "etf") {
    return "ETF";
  }
  if (value === "other") {
    return "altro";
  }
  if (value === "unknown") {
    return "sconosciuto";
  }
  return "";
}

function allowlistLabel(value: string | null | undefined): string {
  if (value === "inside") {
    return "dentro";
  }
  if (value === "outside") {
    return "fuori";
  }
  if (value === "unknown") {
    return "sconosciuto";
  }
  return "";
}

function ClosedTrades({ rows }: { rows: ClosedTrade[] | null | undefined }) {
  if (rows == null) {
    return <p data-testid="closed-trades">Storico chiusure non disponibile</p>;
  }
  if (rows.length === 0) {
    return <p data-testid="closed-trades">Nessuna chiusura Demo</p>;
  }
  return (
    <table className="data-table" data-testid="closed-trades">
      <caption>Storico chiusure Demo</caption>
      <thead>
        <tr>
          <th>Posizione</th>
          <th>Strumento</th>
          <th>Lato</th>
          <th>Quantità</th>
          <th>Risultato</th>
          <th>Chiusura</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.position_id}>
            <td>{row.position_id}</td>
            <td>{row.instrument_id ?? ""}</td>
            <td>{sideLabel(row.side)}</td>
            <td>{row.quantity ?? ""}</td>
            <td data-testid="closed-profit">{row.net_profit ?? ""}</td>
            <td>{row.closed_at ?? ""}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function sideLabel(side: string | null | undefined): string {
  if (side === "BUY") {
    return "acquisto";
  }
  if (side === "SELL") {
    return "vendita";
  }
  return "non disponibile";
}

function ActivityTable({
  rows,
  title,
  empty,
  unavailable,
  testId,
}: {
  rows: Overview["orders"] | undefined;
  title: string;
  empty: string;
  unavailable: string;
  testId: string;
}) {
  if (rows == null) {
    return <p data-testid={testId}>{unavailable}</p>;
  }
  if (rows.length === 0) {
    return <p data-testid={testId}>{empty}</p>;
  }
  return (
    <table className="data-table" data-testid={testId}>
      <caption>{title}</caption>
      <thead>
        <tr>
          <th>Ordine</th>
          <th>Strumento</th>
          <th>Lato</th>
          <th>Quantità</th>
          <th>Valore</th>
          <th>Data</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.order_id}>
            <td>{row.order_id}</td>
            <td>{row.instrument_id ?? "non disponibile"}</td>
            <td>{sideLabel(row.side)}</td>
            <td>{row.quantity ?? "non disponibile"}</td>
            <td>{cashAmount(row.value)}</td>
            <td>{row.opened_at ?? "non disponibile"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function cashAmount(money: Money): string {
  if (!money) {
    return "non disponibile";
  }
  const negative = money.minor_units < 0;
  const absolute = Math.abs(money.minor_units);
  const whole = Math.trunc(absolute / 100).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  const fraction = String(absolute % 100).padStart(2, "0");
  return `${negative ? "-" : ""}${whole},${fraction} ${money.currency}`;
}

function OverviewPage({ loaded }: { loaded: LoadState }) {
  return (
    <>
      <section className="panel">
        <h1>Panoramica</h1>
        <p>Lettura locale di stato, configurazione e budget. Nessun ordine. La lettura Demo, se presente, è solo GET.</p>
        <LoadNotice loaded={loaded} />
        {loaded.status === "ready" ? (
          <>
            <p data-testid="decision">{`Decisione: ${loaded.data.decision.action} · ${loaded.data.decision.reason_codes[0]}`}</p>
            <p data-testid="policy">{`Policy: ${loaded.data.decision.policy.reason}. Broker chiamato: no.`}</p>
            {loaded.data.decision.signal ? (
              <p data-testid="signal">{`Segnale ${loaded.data.decision.signal.signal_id.slice(0, 12)} · direzione ${loaded.data.decision.signal.direction} · eseguibile: no`}</p>
            ) : null}
            {loaded.data.overview.ledger_status === "FX_UNAVAILABLE" ? (
              <p data-testid="cash-alignment">{`Disponibile non calcolato: cassa ${loaded.data.overview.broker_cash?.currency ?? "non osservata"}, budget ${loaded.data.overview.assigned_budget?.currency ?? "non osservato"}. Nessun cambio configurato.`}</p>
            ) : null}
            <PositionsTable positions={loaded.data.overview.positions} />
            <ActivityTable
              rows={loaded.data.overview.orders}
              title="Ordini Demo"
              empty="Nessun ordine Demo"
              unavailable="Ordini non disponibili"
              testId="orders"
            />
            <ActivityTable
              rows={loaded.data.overview.executions}
              title="Esecuzioni Demo"
              empty="Nessuna esecuzione Demo"
              unavailable="Esecuzioni non disponibili"
              testId="executions"
            />
            <ClosedTrades rows={loaded.data.overview.closed_trades} />
            <dl className="facts" data-testid="account-card">
              <div><dt>Piattaforma</dt><dd>Demo eToro</dd></div>
              <div><dt>Connessa</dt><dd>{loaded.data.overview.broker_connected ? "sì" : "no"}</dd></div>
              <div><dt>Conto</dt><dd>{loaded.data.overview.account_label || DEMO_ACCOUNT_FALLBACK}</dd></div>
              <div><dt>Cassa broker</dt><dd>{cashAmount(loaded.data.overview.broker_cash)}</dd></div>
              <div><dt>Ultimo probe</dt><dd>{loaded.data.overview.last_probe_at ?? "non disponibile"}</dd></div>
            </dl>
          </>
        ) : null}
      </section>
      <AmountGrid loaded={loaded} />
    </>
  );
}

function PortfolioPage({ loaded }: { loaded: LoadState }) {
  return (
    <section className="panel">
      <h1>Portafoglio</h1>
      <p>Il ledger espone solo il budget osservabile. I valori assenti restano non disponibili.</p>
      <p>Disponibile = min(cassa broker, budget − posizioni esterne) − riservato − buffer. Se la cassa e il budget sono in valute diverse e non c’è un cambio configurato, il disponibile resta non disponibile.</p>
      <LoadNotice loaded={loaded} />
      {loaded.status === "ready" ? (
        <>
          <p>{`Stato ledger: ${loaded.data.overview.ledger_status}`}</p>
          <p>{`Registrazioni: ${loaded.data.overview.journal_entry_count}. Giornale bilanciato: ${loaded.data.overview.journal_balanced ? "sì" : "no"}.`}</p>
          <ReconciliationLine report={loaded.data.overview.reconciliation} />
        </>
      ) : null}
    </section>
  );
}

function ReconciliationLine({ report }: { report: Overview["reconciliation"] }) {
  if (!report || report.status === "UNAVAILABLE") {
    return <p data-testid="reconciliation">Riconciliazione non disponibile. Nessun ordine. Lo stato resta OBSERVATION.</p>;
  }
  if (report.status === "MATCH") {
    return <p data-testid="reconciliation">Riconciliazione: i conti coincidono. Nessun ordine. Lo stato resta OBSERVATION.</p>;
  }
  const classes = report.classes.length === 0 ? "non classificata" : report.classes.join(", ");
  return (
    <p data-testid="reconciliation">
      {`Riconciliazione: differenza ${classes}. Nessun ordine. Lo stato resta OBSERVATION.`}
    </p>
  );
}

function ConfigurationPage({ loaded }: { loaded: LoadState }) {
  return (
    <section className="panel">
      <h1>Configurazione</h1>
      <LoadNotice loaded={loaded} />
      {loaded.status === "ready" ? <ConfigurationDetails configuration={loaded.data.configuration} /> : null}
    </section>
  );
}

function ConfigurationDetails({ configuration }: { configuration: Configuration }) {
  return (
    <dl className="facts">
      <div><dt>Versione</dt><dd>{configuration.version_number}</dd></div>
      <div><dt>Autore</dt><dd>{configuration.author}</dd></div>
      <div><dt>Motivo</dt><dd>{configuration.reason}</dd></div>
      <div><dt>Impronta</dt><dd><code>{configuration.content_sha256}</code></dd></div>
      <div><dt>Confine di barra</dt><dd>{configuration.valid_from_bar_open}</dd></div>
      <div><dt>Valuta</dt><dd>{configuration.budget.base_currency ?? "non disponibile"}</dd></div>
      <div><dt>Budget</dt><dd>{minorOrMissing(configuration.budget.assigned_budget_minor, configuration.budget.base_currency)}</dd></div>
      <div><dt>Perdita massima</dt><dd>{lossLimit(configuration.economic.max_loss_bps, configuration.economic.max_loss_action)}</dd></div>
      <div><dt>Orizzonte</dt><dd>{configuration.economic.horizon ?? "non disponibile"}</dd></div>
      <div><dt>Universo</dt><dd>{configuration.economic.allowlist_universe ?? "non disponibile"}</dd></div>
      <div><dt>Direzione</dt><dd>{configuration.economic.allowlist_direction ?? "non disponibile"}</dd></div>
      <div><dt>Leva</dt><dd>{configuration.economic.leverage ?? "non disponibile"}</dd></div>
      <div><dt>Benchmark</dt><dd>{benchmarkLabel(configuration.economic.benchmark_name, configuration.economic.benchmark_return_basis)}</dd></div>
      <div><dt>Soglie tecniche</dt><dd>{configuration.economic.technical_calibratable ? "calibrabili" : "non disponibile"}</dd></div>
      <div><dt>File</dt><dd><code>{configuration.economic.source_path}</code></dd></div>
    </dl>
  );
}

function AmountGrid({ loaded }: { loaded: LoadState }) {
  const overview = loaded.status === "ready" ? loaded.data.overview : null;
  const fields = [
    ["Equity", overview?.equity ?? null],
    ["Cash broker", overview?.broker_cash ?? null],
    ["Budget assegnato", overview?.assigned_budget ?? null],
    ["Disponibile", overview?.available_cash ?? null],
    ["Cash riservato", overview?.reserved_cash ?? null],
    ["Cash buffer", overview?.cash_buffer ?? null],
  ] as const;
  return (
    <section className="kpi-grid" aria-label="Indicatori di budget">
      {fields.map(([label, value]) => (
        <article className="kpi" key={label}>
          <span>{label}</span>
          <strong>{formatMoney(value)}</strong>
        </article>
      ))}
    </section>
  );
}

function formatMoney(value: Money | null): string {
  if (!value) {
    return "non disponibile";
  }
  return `${value.minor_units} ${value.currency}`;
}

function lossLimit(bps: number | null, action: string | null): string {
  if (bps == null) {
    return "non disponibile";
  }
  const whole = Math.trunc(bps / 100);
  const fraction = Math.abs(bps % 100);
  const percent = fraction === 0 ? `${whole}%` : `${whole}.${String(fraction).padStart(2, "0")}%`;
  return action ? `${percent}, ${action}` : percent;
}

function benchmarkLabel(name: string | null, basis: string | null): string {
  if (!name) {
    return "non disponibile";
  }
  return basis ? `${name} ${basis}` : name;
}

function minorOrMissing(minor: number | null, currency: string | null): string {
  if (minor == null || currency == null) {
    return "non disponibile";
  }
  return `${minor} ${currency}`;
}

function LoadNotice({ loaded }: { loaded: LoadState }) {
  if (loaded.status === "loading") {
    return <p role="status">Caricamento della lettura locale.</p>;
  }
  if (loaded.status === "error") {
    return <p role="alert">{loaded.message}</p>;
  }
  return null;
}

function DataPage({ loaded }: { loaded: LoadState }) {
  const live = loaded.status === "ready" && loaded.data.health.network;
  return (
    <section className="panel">
      <h1>Dati e agente</h1>
      <p>
        {live
          ? "I prezzi arrivano da una lettura di mercato in sola lettura. News, macro e fondamentali restano fixture. Nessun ordine."
          : "I canali arrivano da una fixture locale. Nessuna rete e nessuna chiamata al broker."}
      </p>
      <LoadNotice loaded={loaded} />
      {loaded.status === "ready" ? (
        <>
          <MarketFixture channel={loaded.data.health.channels.find((channel) => channel.channel === "prices")} />
          <NewsFixture channel={loaded.data.health.channels.find((channel) => channel.channel === "news")} />
          <SlowFixture testId="macro-data" label="macro" channel={loaded.data.health.channels.find((channel) => channel.channel === "macro")} />
          <SlowFixture testId="fundamentals-data" label="fondamentali" channel={loaded.data.health.channels.find((channel) => channel.channel === "fundamentals")} />
          <ChannelTable health={loaded.data.health} />
        </>
      ) : null}
    </section>
  );
}

function MarketFixture({ channel }: { channel: DataChannel | undefined }) {
  if (!channel || channel.accepted_count === 0) {
    return <p data-testid="market-data">Canale prezzi assente. La decisione resta NO-TRADE.</p>;
  }
  const kind = channel.source === "etoro-public-api" ? "Prezzi di mercato" : "Fixture prezzi";
  if (channel.stale) {
    return (
      <p data-testid="market-data">
        {`${kind} stale (${channel.source}, ${channel.event_time ?? "senza orario"}, licenza ${channel.license ?? "non verificata"}). La decisione resta NO-TRADE.`}
      </p>
    );
  }
  return (
    <p data-testid="market-data">
      {`${kind} ${channel.source}. Evento ${channel.event_time ?? "non disponibile"}. Licenza ${channel.license ?? "non verificata"}.${channel.source === "etoro-public-api" ? " La decisione resta NO-TRADE." : ""}`}
    </p>
  );
}

function NewsFixture({ channel }: { channel: DataChannel | undefined }) {
  if (!channel || channel.accepted_count === 0) {
    return <p data-testid="news-data">Canale news assente. La decisione resta NO-TRADE.</p>;
  }
  if (channel.stale) {
    return (
      <p data-testid="news-data">
        {`Fixture news stale (${channel.source}, ${channel.event_time ?? "senza orario"}, licenza ${channel.license ?? "non verificata"}). La decisione resta NO-TRADE.`}
      </p>
    );
  }
  return (
    <p data-testid="news-data">
      {`Fixture news ${channel.source}. Evento ${channel.event_time ?? "non disponibile"}. Licenza ${channel.license ?? "non verificata"}. La decisione resta NO-TRADE.`}
    </p>
  );
}

function SlowFixture({ testId, label, channel }: { testId: string; label: string; channel: DataChannel | undefined }) {
  if (!channel || channel.accepted_count === 0) {
    return <p data-testid={testId}>{`Canale ${label} assente. La decisione resta NO-TRADE.`}</p>;
  }
  if (channel.stale) {
    return (
      <p data-testid={testId}>
        {`Fixture ${label} stale (${channel.source}, ${channel.event_time ?? "senza orario"}, licenza ${channel.license ?? "non verificata"}). La decisione resta NO-TRADE.`}
      </p>
    );
  }
  return (
    <p data-testid={testId}>
      {`Fixture ${label} ${channel.source}. Pubblicazione ${channel.event_time ?? "non disponibile"}. Licenza ${channel.license ?? "non verificata"}. La decisione resta NO-TRADE.`}
    </p>
  );
}

function ChannelTable({ health }: { health: DataHealth }) {
  return (
    <table className="data-table">
      <caption>{`Fonte ${health.source}. Rete: ${health.network ? "sì" : "no"}.`}</caption>
      <thead>
        <tr>
          <th>Canale</th>
          <th>Stato</th>
          <th>Qualità</th>
          <th>Accettati</th>
        </tr>
      </thead>
      <tbody>
        {health.channels.map((channel) => (
          <tr key={channel.channel}>
            <td>{channel.channel}</td>
            <td>{channel.status}</td>
            <td>{channel.quality_millis}</td>
            <td>{channel.accepted_count}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function AuditPage({ loaded }: { loaded: LoadState }) {
  const audit = loaded.status === "ready" ? loaded.data.audit : null;
  return (
    <section className="panel">
      <h1>Audit</h1>
      <p>Catena append-only delle decisioni. Il digest esterno non è collegato e nessuna chiave di firma è presente.</p>
      <LoadNotice loaded={loaded} />
      {audit ? (
        <>
          <p>{`Catena: ${audit.chain_valid ? "integra" : "alterata"}. Eventi: ${audit.event_count}.`}</p>
          <p>{`Digest del giorno sigillato: ${audit.daily_digest_sealed ? "sì" : "no"}. Storage esterno: ${audit.durable ? "collegato" : "non collegato"}.`}</p>
          <p><code>{audit.head_hash ?? "non disponibile"}</code></p>
        </>
      ) : null}
    </section>
  );
}

function Page({ title }: { title: string }) {
  return (
    <section className="panel">
      <h1>{title}</h1>
      <p>Vista non ancora collegata al read model. Nessun ordine da questa schermata.</p>
    </section>
  );
}

function ChatPage() {
  const [message, setMessage] = useState("");
  const [answer, setAnswer] = useState<ChatTurn | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    const question = message.trim();
    if (!question) {
      return;
    }
    setError(null);
    try {
      const body = await getJson<Envelope<ChatTurn>>(`/api/v1/chat?q=${encodeURIComponent(question)}`);
      setAnswer(body.data);
    } catch {
      setAnswer(null);
      setError("Lettura chat non riuscita. Nessun ordine è stato inviato.");
    }
  }

  return (
    <section className="panel">
      <h1>Chat</h1>
      <p>Canale informativo sullo stato locale. Il testo libero non crea ordini e non esegue comandi.</p>
      <form onSubmit={onSubmit}>
        <label htmlFor="chat-input">Messaggio</label>
        <textarea
          id="chat-input"
          className="chat-box"
          value={message}
          onChange={(event) => setMessage(event.target.value)}
          placeholder="Chiedi della decisione, delle posizioni o dei dati"
        />
        <button type="submit">Chiedi</button>
      </form>
      {error ? <p role="alert">{error}</p> : null}
      {answer ? (
        <>
          <p data-testid="chat-answer">{answer.text}</p>
          <p data-testid="chat-executed">{answer.executed ? "eseguito" : "nessuna azione"}</p>
          {answer.citations.length > 0 ? (
            <ol data-testid="chat-citations">
              {answer.citations.map((citation) => (
                <li key={citation.citation_id}>{`${citation.label}: ${citation.resource_id}`}</li>
              ))}
            </ol>
          ) : null}
        </>
      ) : null}
    </section>
  );
}

export function AppRoutes() {
  return <Shell />;
}
