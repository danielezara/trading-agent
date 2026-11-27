import { displayAccount, formatCash, readDemoOverview, type DemoPosition } from "../lib/demo-read";

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
      {overview.probeErrorClass ? <p>Errore: {overview.probeErrorClass}</p> : null}
    </main>
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
