import { useEffect, useState } from "react";
import { NavLink, Route, Routes } from "react-router-dom";
import { getJson } from "./api/client";
import { assertSkeletonApiBase } from "./api/policy";
import type { Configuration, Envelope, Money, OperationalState, Overview } from "./api/types";
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
          <Route path="/dati" element={<Page title="Dati e agente" />} />
          <Route path="/alert" element={<Page title="Alert" />} />
          <Route path="/audit" element={<Page title="Audit" />} />
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
    ]).then(([overview, configuration, operational]) => {
      if (!cancelled) {
        setLoaded({
          status: "ready",
          data: {
            overview: overview.data,
            configuration: configuration.data,
            operational: operational.data,
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

function OverviewPage({ loaded }: { loaded: LoadState }) {
  return (
    <>
      <section className="panel">
        <h1>Panoramica</h1>
        <p>Lettura locale di stato, configurazione e budget. Nessun ordine e nessuna chiamata al broker.</p>
        <LoadNotice loaded={loaded} />
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
      <LoadNotice loaded={loaded} />
      {loaded.status === "ready" ? <p>{`Stato ledger: ${loaded.data.overview.ledger_status}`}</p> : null}
    </section>
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
      <div><dt>Valuta</dt><dd>{configuration.budget.base_currency ?? "non disponibile"}</dd></div>
      <div><dt>Budget</dt><dd>{minorOrMissing(configuration.budget.assigned_budget_minor, configuration.budget.base_currency)}</dd></div>
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

function Page({ title }: { title: string }) {
  return (
    <section className="panel">
      <h1>{title}</h1>
      <p>Vista non ancora collegata al read model. Nessun ordine da questa schermata.</p>
    </section>
  );
}

function ChatPage() {
  return (
    <section className="panel">
      <h1>Chat</h1>
      <p>
        Canale informativo non collegato. Il testo libero non crea ordini e non
        esegue comandi.
      </p>
      <label htmlFor="chat-input">Messaggio</label>
      <textarea
        id="chat-input"
        className="chat-box"
        disabled
        placeholder="Chat non disponibile nello scheletro"
      />
    </section>
  );
}

export function AppRoutes() {
  return <Shell />;
}
