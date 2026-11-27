# Agente trading — scheletro Demo

Modular monolith in **OBSERVATION**. Questo repository avvia la struttura di backend, dashboard e Postgres locale. Non parla con alcun broker, non contiene chiavi e non può inviare ordini.

Lo stato operativo resta `OBSERVATION`. `allowsActive()` richiede `PASS` su RF-044 e RF-045. RF-059 non è richiesto. RF-045 resta `UNVERIFIED`, quindi `ACTIVE` resta chiuso. Strategia, rischio e portafoglio rispondono `NO-TRADE`.

## Struttura

```text
backend/     monolith Kotlin / Spring Boot 3 / Java 21
frontend/    dashboard React / TypeScript, sola lettura
infra/       Postgres 16 locale e schemi
contracts/   OpenAPI delle sole route dello scheletro
```

Moduli del backend, un solo artefatto: identity, configuration, ingestion, features, corporate action, strategy, portfolio, risk, ledger, OMS, executor, reconciliation, operational state, audit, read model, chat, scheduler, outbox. I confini di package sono verificati con ArchUnit. Nessuna classe applica un client HTTP.

ADR-002 è adottato per questo scheletro (Kotlin, Spring Boot 3, Java 21). jOOQ e Flyway non sono ancora collegati: gli schemi stanno in `infra/postgres/init` e il processo non apre una connessione.

## Configurazione

I default sono in `backend/src/main/resources/application.yml` e in [`.env.example`](.env.example). Non ci sono segreti. Il backend rifiuta l’avvio se:

- `TRADING_ENVIRONMENT` non è `DEMO`;
- `TRADING_OPERATIONAL_STATE` non è `OBSERVATION`;
- `TRADING_BROKER_ENABLED` non è `false`;
- `TRADING_BROKER_BASE_URL` non è vuoto;
- è presente una variabile di credenziali broker o LLM (`ETORO_API_KEY`, `ETORO_API_SECRET`, `ETORO_USER_KEY`, `ETORO_PASSWORD`, `ETORO_TOKEN`, `BROKER_API_KEY`, `BROKER_API_SECRET`, `LLM_API_KEY`, `OPENAI_API_KEY`).

Il valore della variabile non viene scritto nel messaggio di errore. Il servizio ascolta su `127.0.0.1`. L’identità non è collegata: non esporre la porta.

Le chiavi Demo locali sono `ETORO_DEMO_API_KEY` e `ETORO_DEMO_USER_KEY`, lette solo dall’ambiente del processo. I nomi con valore vuoto stanno in [`backend/.env.example`](backend/.env.example); `backend/.env` è ignorato da git. Se mancano, la decisione resta `NO-TRADE` con `CAPABILITY_UNVERIFIED`. I valori non vengono scritti nei log.

All’avvio il processo fa al massimo una GET autenticata verso il portafoglio Demo pubblico. Se la lettura riesce, la riga RF-044 diventa `PASS` e la cassa osservata compare in panoramica. `401`/`403` segnano `FAIL`. Senza chiavi, timeout o rate limit la riga resta `UNVERIFIED`. Non esiste alcun POST. Lo stato resta `OBSERVATION`.

`POSTGRES_*` serve solo a Compose. Il backend non le legge.

## Avvio locale

Backend:

```bash
cd backend
./gradlew bootRun
```

Dashboard, senza chiamate di rete:

```bash
cd frontend
npm ci
npm run dev
```

Postgres locale, opzionale e non usato dal processo:

```bash
docker compose -f infra/docker-compose.yml up -d
```

La password `local-dev-only` è un segnaposto del database locale, non una credenziale del servizio. Il vincolo `control.operational_state` accetta soltanto `OBSERVATION`.

## API

Contratto: [`contracts/openapi/skeleton-v1.yaml`](contracts/openapi/skeleton-v1.yaml).

- `GET /health/live`
- `GET /health/ready-trading` → 503, il trading non è pronto
- `GET /health/ready-admin` → 503, il datastore non è collegato
- `GET /api/v1/system/status`
- `GET /api/v1/evidence-gate`
- `GET /api/v1/configurations` e `GET /api/v1/configurations/active`
- `GET /api/v1/operational-state`
- `GET /api/v1/data-health`
- `GET /api/v1/decisions/latest`
- `GET /api/v1/audit`
- `GET /api/dashboard/v1/session`
- `GET /api/dashboard/v1/overview`

La configurazione è versionata e immutabile: autore, motivo, istante e impronta SHA-256 del contenuto. I parametri economici e di strategia stanno in `backend/src/main/resources/config/observation-v1.yaml`: budget 500 EUR (50000 minor unit), perdita massima 10% del budget con blocco, orizzonte in giorni, allowlist large-cap e ETF solo long a leva 1, benchmark MSCI World total return. L’ingresso e l’uscita sono `closed-bar-trend/1`: prezzi eToro, solo barre da 5 minuti già chiuse, ingresso dopo due chiusure al rialzo, uscita dopo due chiusure al ribasso o stop a 2×ATR o 390 barre. Le barre sono la GET delle candele `FiveMinutes`; una candela ancora aperta o un buco non viene riempito. Se le barre chiuse contigue non bastano, la decisione resta `NO-TRADE`. La size è il minimo tra rischio 0,50% del budget, cap 15% per strumento e budget residuo dopo il 20% di cassa. Le soglie tecniche restano calibrabili. La cassa in panoramica è l’importo nativo che eToro riporta, per valuta, senza conversione. Il registro movimenti copia `amount` e `currency` delle transazioni del conto Cash, una pagina, senza tasso né valuta originale. Lo storico operazioni in Attività è la pagina history Demo: se è vuota resta vuota. Capability e licenze restano `UNVERIFIED`, quindi la decisione viva è `NO-TRADE` con motivo `CAPABILITY_UNVERIFIED`. Una versione nuova non diventa attiva se esiste un intent in volo; l’API HTTP non espone la scrittura. Il ledger calcola il disponibile in minor unit, `min(broker cash, budget − holdings esterni) − riservato − buffer`, e lascia `null` ciò che non è osservato. Il giornale in partita doppia è provato da una fixture e non è caricato nel saldo vivo: la panoramica resta `UNOBSERVED` con zero registrazioni. Lo stato operativo del processo resta `OBSERVATION`; la tabella evento×stato è pura e non viene applicata al processo. L’ingestione legge solo una fixture sul classpath, con provenienza, deduplica, staleness e qualità deterministica. Licenza e contratto non verificati azzerano la qualità. La decisione è sempre `NO-TRADE`, con lo stesso input che produce lo stesso hash. Una versione di configurazione nuova diventa attiva solo al confine della barra da 5 minuti per cui è stata schedulata, e solo se non ci sono intent in `proposed`, `risk_reserved`, `dispatch_pending`, `sent` o `unknown`. Ogni decisione è appesa a una catena di audit con hash; il digest giornaliero si può sigillare una sola volta e lo storage esterno non è collegato. Il gate di policy rifiuta ogni tentativo di invio se manca intent, prenotazione, audit o outbox, e non chiama il broker nemmeno quando i quattro riferimenti ci sono.

In sviluppo la dashboard interroga queste GET tramite il proxy locale di Vite verso `127.0.0.1:8080`.

Gli importi assenti sono `null`, non zero. Ogni altro metodo risponde `403` con `OBSERVATION_MUTATIONS_DISABLED`.

## Verifica

```bash
bash scripts/check-skeleton-guardrails.sh
(cd backend && ./gradlew test)
(cd frontend && npm test && npm run build)
```

La CI esegue gli stessi controlli, più `docker compose config`.
