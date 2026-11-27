import { displayAccount, formatCash, readDemoOverview, type CashMovement, type ClosedTrade, type DemoPosition } from "../lib/demo-read";

export const dynamic = "force-dynamic";
export const fetchCache = "force-no-store";

export default async function Page() {
  const overview = await readDemoOverview({
    env: process.env,
    fetchImpl: fetch,
  });
  const rows = [
    ["Piattaforma", overview.platform],
    ["Connessa", overview.connected ? "sì" : "no"],
    ["Conto", displayAccount(overview.accountLabel)],
    ["Cassa broker", formatCash(overview.brokerCash)],
    ["Ultimo probe", overview.lastProbeAt ?? "non disponibile"],
    ["Stato", overview.operationalState],
    ["Ordini", "nessuno"],
  ];
  return (
    <main>
      <p className="product">Agente trading</p>
      <h1>Panoramica</h1>
      <p>Lettura Demo sul server. Nessun ordine.</p>
      <dl>
        {rows.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      <Positions positions={overview.positions} />
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

function Positions({ positions }: { positions: DemoPosition[] | null }) {
  if (positions == null) {
    return <p>Posizioni non disponibili</p>;
  }
  if (positions.length === 0) {
    return <p>Nessuna posizione</p>;
  }
  return (
    <table>
      <caption>Posizioni Demo</caption>
      <thead>
        <tr>
          <th>Strumento</th>
          <th>Nome</th>
          <th>Quantità</th>
          <th>Valore</th>
          <th>Quotazione</th>
          <th>Ora</th>
        </tr>
      </thead>
      <tbody>
        {positions.map((position, index) => (
          <tr key={`${position.instrumentId}-${index}`}>
            <td>{position.instrumentId}</td>
            <td>{position.displayName ?? ""}</td>
            <td>{position.quantity ?? "non disponibile"}</td>
            <td>{formatCash(position.value)}</td>
            <td>{position.quotePrice ?? ""}</td>
            <td>{position.quoteTime ?? ""}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
