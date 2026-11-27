import { NavLink, Route, Routes } from "react-router-dom";
import { assertSkeletonApiBase } from "./api/policy";
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
          <span className="badge badge-state">OBSERVATION</span>
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
          <Route path="/" element={<Page title="Panoramica" />} />
          <Route path="/portafoglio" element={<Page title="Portafoglio" />} />
          <Route path="/performance" element={<Page title="Performance" />} />
          <Route path="/attivita" element={<Page title="Attività" />} />
          <Route path="/dati" element={<Page title="Dati e agente" />} />
          <Route path="/alert" element={<Page title="Alert" />} />
          <Route path="/audit" element={<Page title="Audit" />} />
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/configurazione" element={<Page title="Configurazione" />} />
        </Routes>
      </main>
    </>
  );
}

function Page({ title }: { title: string }) {
  return (
    <>
      <section className="panel">
        <h1>{title}</h1>
        <p>
          Scheletro in sola lettura. Lo stato resta OBSERVATION: nessun ordine,
          nessuna chiamata al broker e nessun dato di portafoglio inventato.
        </p>
      </section>
      {title === "Panoramica" ? <OverviewPlaceholders /> : null}
    </>
  );
}

function OverviewPlaceholders() {
  const fields = [
    "Equity",
    "Cash broker",
    "Budget assegnato",
    "Disponibile",
    "Cash riservato",
    "Esposizione",
  ];
  return (
    <section className="kpi-grid" aria-label="Indicatori non disponibili">
      {fields.map((field) => (
        <article className="kpi" key={field}>
          <span>{field}</span>
          <strong>non disponibile</strong>
        </article>
      ))}
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
