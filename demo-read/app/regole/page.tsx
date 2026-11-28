export const dynamic = "force-dynamic";

export default function RulesPage() {
  return (
    <main>
      <p className="product">Agente trading</p>
      <h1>Regole</h1>
      <p>Solo quello che il programma applica. Le cifre sono quelle del config attivo.</p>
      <p>Il giro acceso è breakout/1.</p>
      <nav className="nav">
        <a href="/">Portafoglio</a>
        <a href="/regole">Regole</a>
      </nav>
      <h2>Ingresso</h2>
      <ul className="rules">
        <li>Compra se la chiusura supera il massimo delle ultime 20 candele da 5 minuti, esclusa la corrente. I prezzi sono quelli di eToro. Se lo storico non basta, non c&apos;è segnale.</li>
        <li>Solo Bitcoin e le coppie spot abilitate, solo long, leva 1.</li>
        <li>Universo: Bitcoin, simbolo BTC, e le coppie della whitelist che passano i filtri di ingresso. Una coppia esclusa non si compra.</li>
        <li>La leva 1 si scrive sull&apos;ordine solo se eToro la indica sullo strumento. Se il campo non c&apos;è, l&apos;ordine parte senza quel campo.</li>
        <li>Budget 500 EUR.</li>
        <li>Ogni ingresso è 75 EUR, e 75 EUR è anche il massimo di una posizione. L&apos;esposizione del breakout è 400 EUR. Al massimo 4 posizioni.</li>
        <li>Se più coppie segnalano insieme, passa prima l&apos;id di catalogo in ordine alfabetico.</li>
        <li>Non si aumenta una posizione già aperta.</li>
        <li>Stop perdita 10%, cioè 50 EUR. Se la perdita aperta che eToro riporta, in euro col suo tasso, arriva a 50 EUR, i nuovi ingressi si bloccano.</li>
        <li>Non ricompra Bitcoin se l&apos;ordine 397105707 è ancora aperto.</li>
        <li>Le 16 posizioni preesistenti, aperte prima delle 16:13 del 27 novembre 2026 (ora di Roma), sono fuori: nessun ordine su quelle.</li>
        <li>Se manca il tasso euro/dollaro di eToro, l&apos;ordine non parte.</li>
        <li>Il giro legge una barra da 5 minuti alla volta. Il kill switch, se è inserito, blocca i nuovi ordini. Adesso è spento.</li>
      </ul>
      <h2>Uscita</h2>
      <ul className="rules">
        <li>L&apos;uscita è la chiusura sotto il minimo delle ultime 10 candele, oppure lo stop al 2% dal prezzo di esecuzione. Lo stop non garantisce quel prezzo.</li>
        <li>Il giro acceso non invia vendite: non chiude le 16 posizioni preesistenti e non vende da solo.</li>
      </ul>
      <h2>Pagina</h2>
      <ul className="rules">
        <li>Il portafoglio si aggiorna da eToro a ogni visita e poi ogni 15 secondi.</li>
        <li>Il valore è la somma dei valori eToro delle posizioni del bot. Se un ordine è ancora aperto e il valore manca, il totale dice cosa manca e non inventa la cifra.</li>
      </ul>
    </main>
  );
}
