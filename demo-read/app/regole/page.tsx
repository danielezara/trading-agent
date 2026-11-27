export const dynamic = "force-dynamic";

export default function RulesPage() {
  return (
    <main>
      <p className="product">Agente trading</p>
      <h1>Regole</h1>
      <p>Solo quello che il programma applica. Le cifre sono quelle del config attivo.</p>
      <nav className="nav">
        <a href="/">Portafoglio</a>
        <a href="/regole">Regole</a>
      </nav>
      <h2>Ingresso</h2>
      <ul className="rules">
        <li>Compra dopo due chiusure al rialzo su barre da 5 minuti già chiuse. I prezzi sono quelli di eToro.</li>
        <li>Solo azioni e ETF, solo long, leva 1.</li>
        <li>Universo: AAPL, MSFT, GOOG, NVDA, META, JPM, SPY e SWDA.L.</li>
        <li>Budget 500 EUR.</li>
        <li>Rischio 2,50 EUR per ingresso.</li>
        <li>Massimo 75 EUR a titolo.</li>
        <li>Massimo 8 posizioni del bot.</li>
        <li>Restano almeno 100 EUR di cassa. L&apos;investito non supera 400 EUR.</li>
        <li>Il rischio aperto complessivo è 10 EUR: al massimo quattro ingressi da 2,50 EUR.</li>
        <li>Stop perdita 10%, cioè 50 EUR. Se la perdita aperta che eToro riporta, in euro col suo tasso, arriva a 50 EUR, i nuovi ingressi si bloccano.</li>
        <li>Non riapre un titolo che il bot ha già.</li>
        <li>Le 16 posizioni preesistenti, aperte prima delle 16:13 del 27 novembre 2026 (ora di Roma), sono fuori: nessun ordine su quelle.</li>
        <li>Se manca il tasso euro/dollaro di eToro, l&apos;ordine non parte.</li>
        <li>Il giro legge una barra da 5 minuti alla volta. Il kill switch, se è inserito, blocca i nuovi ordini. Adesso è spento.</li>
      </ul>
      <h2>Uscita</h2>
      <ul className="rules">
        <li>La regola di uscita è: vende dopo due chiusure al ribasso, oppure stop a 2×ATR, oppure dopo 390 barre da 5 minuti.</li>
        <li>Quel calcolo c&apos;è nel codice. Il giro acceso non invia vendite e non chiude le posizioni.</li>
      </ul>
      <h2>Pagina</h2>
      <ul className="rules">
        <li>Il portafoglio si aggiorna da eToro a ogni visita e poi ogni 15 secondi.</li>
        <li>Il valore è la somma dei valori eToro delle posizioni del bot. Se un ordine è ancora aperto e il valore manca, il totale dice cosa manca e non inventa la cifra.</li>
      </ul>
    </main>
  );
}
