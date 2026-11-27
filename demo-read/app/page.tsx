import { displayAccount, formatCash, readDemoOverview } from "../lib/demo-read";

export const dynamic = "force-dynamic";

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
      {overview.probeErrorClass ? <p>Errore: {overview.probeErrorClass}</p> : null}
    </main>
  );
}
