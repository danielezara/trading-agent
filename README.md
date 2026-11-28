# Agente trading — Demo eToro (stack unico)

Modular monolith **Kotlin / Spring Boot 3 / Java 21**. Un solo comando per leggere e operare sulla **Public API Demo** eToro. Architettura **gratis**: niente Node obbligatorio, niente SaaS a pagamento.

Dettaglio: [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Avvio

```bash
cd backend
export TRADING_ENVIRONMENT=DEMO
export TRADING_OPERATIONAL_STATE=OBSERVATION
export TRADING_BROKER_ENABLED=false
export TRADING_BROKER_BASE_URL=
export ETORO_DEMO_API_KEY=...   # solo Demo
export ETORO_DEMO_USER_KEY=...
./gradlew bootRun
```

Console: [http://127.0.0.1:8080/](http://127.0.0.1:8080/)

- Whitelist crypto e candele: `GET /api/v1/whitelist`
- Apri long Demo: `POST /api/v1/demo/orders/open` con `{"instrument_id":"100109","amount":"50.00","leverage":1}`
- Chiudi: `POST /api/v1/demo/orders/close/{positionId}`

Il processo rifiuta variabili **real**/broker generico e path non-Demo. Il kill switch blocca nuovi open/close.

## Struttura

```text
backend/     monolith + console Thymeleaf
infra/       Postgres 16 opzionale
contracts/   OpenAPI scheletro
demo-read/   legacy Next.js (non necessario)
frontend/    legacy React (non necessario)
```

Postgres locale opzionale:

```bash
docker compose -f infra/docker-compose.yml up -d
```

## Sicurezza operativa

- Solo `TRADING_ENVIRONMENT=DEMO`
- `TRADING_BROKER_ENABLED=false` e base URL vuota
- Chiavi ammesse: solo `ETORO_DEMO_API_KEY` / `ETORO_DEMO_USER_KEY`
- Mutazioni HTTP consentite soltanto sotto `/api/v1/demo/`
