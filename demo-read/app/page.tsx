import { BotPortfolio } from "./bot-portfolio";
import { displayAccount, formatCash, readDemoOverview, readProgramBook, type CashMovement, type ClosedTrade } from "../lib/demo-read";
import { serverFetch } from "../lib/server-fetch";

export const dynamic = "force-dynamic";
export const fetchCache = "force-no-store";

export default async function Page() {
  const [overview, book] = await Promise.all([
    readDemoOverview({
      env: process.env,
      fetchImpl: serverFetch,
      quotes: false,
    }),
    readProgramBook({
      env: process.env,
      fetchImpl: serverFetch,
    }),
  ]);
  const rows = [
    ["Piattaforma", overview.platform],
    ["Connessa", overview.connected ? "sì" : "no"],
    ["Conto", displayAccount(overview.accountLabel)],
    ["Cassa broker", formatCash(overview.brokerCash)],
    ["Ultimo probe", overview.lastProbeAt ?? "non disponibile"],
    ["Stato", overview.operationalState],
    ["Invii da questa pagina", "no"],
  ];
  return (
    <main>
      <p className="product">Agente trading</p>
      <h1>Panoramica</h1>
      <p>Lettura Demo sul server. Nessun ordine in questa pagina.</p>
      <BotPortfolio initial={book} />
      <dl>
        {rows.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      <Movements rows={overview.cashMovements} />
      <History rows={overview.closedTrades} />
      {overview.probeErrorClass ? <p>Errore: {overview.probeErrorClass}</p> : null}
    </main>
  );
}

function Movements({ rows }: { rows: CashMovement[] | null }) {
  if (rows == null) {
    return <p>Movimenti non disponibili</p>;
  }
  if (rows.length === 0) {
    return <p>Nessun movimento</p>;
  }
  return (
    <table>
      <caption>Registro movimenti</caption>
      <thead>
        <tr>
          <th>Data</th>
          <th>Tipo</th>
          <th>Verso</th>
          <th>Stato</th>
          <th>Importo</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={`${row.postedAt ?? "movimento"}-${index}`}>
            <td>{row.postedAt ?? ""}</td>
            <td>{row.kind}</td>
            <td>{row.direction === "credit" ? "accredito" : "addebito"}</td>
            <td>{row.status}</td>
            <td>{formatCash({ minorUnits: row.minorUnits, currency: row.currency })}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function History({ rows }: { rows: ClosedTrade[] | null }) {
  if (rows == null) {
    return <p>Storico chiusure non disponibile</p>;
  }
  if (rows.length === 0) {
    return <p>Storico operazioni vuoto</p>;
  }
  return (
    <table>
      <caption>Storico operazioni</caption>
      <thead>
        <tr>
          <th>Strumento</th>
          <th>Lato</th>
          <th>Quantità</th>
          <th>Profitto</th>
          <th>Chiusura</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={`${row.instrumentId ?? "chiusura"}-${index}`}>
            <td>{row.instrumentId ?? ""}</td>
            <td>{row.side ?? ""}</td>
            <td>{row.quantity ?? ""}</td>
            <td>{row.netProfit ?? ""}</td>
            <td>{row.closedAt ?? ""}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

