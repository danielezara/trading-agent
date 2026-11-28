# Architettura — stack unico e gratis

Un solo runtime: **Kotlin + Spring Boot 3 + Java 21 + Gradle**.

Niente Node obbligatorio per usare lettura e operatività Demo eToro. Nessun SaaS a pagamento: Public API Demo eToro, processo locale, Postgres opzionale via Docker.

## Cosa gira

```text
backend/     API + console HTML (Thymeleaf) + loop breakout Demo
infra/       Postgres locale opzionale (gratis)
contracts/   OpenAPI scheletro
```

`demo-read/` e `frontend/` restano legacy (React/Next). Non servono al percorso supportato.

## Capacità eToro Demo

| Azione | Come |
|--------|------|
| Portafoglio / cassa / quote | GET Public API Demo |
| Whitelist + candele 5m | `GET /api/v1/whitelist` |
| Apri long by amount | `POST /api/v1/demo/orders/open` → Demo market-open |
| Chiudi posizione | `POST /api/v1/demo/orders/close/{positionId}` |
| Loop automatico breakout | già nel processo (solo Demo, leva 1) |

Il broker generico resta spento (`TRADING_BROKER_ENABLED=false`). I path **real** sono vietati nel codice. Kill switch blocca open/close.

## Avvio

```bash
cd backend
# export ETORO_DEMO_API_KEY=...
# export ETORO_DEMO_USER_KEY=...
./gradlew bootRun
```

Apri `http://127.0.0.1:8080/`.

## Costi

- JDK / Gradle: gratis  
- Spring / Thymeleaf: gratis  
- eToro Public API Demo: gratis per conti abilitati  
- Hosting: locale o qualsiasi JVM host; nessun obbligo Cursor Apps / Vercel  
