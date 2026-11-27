#!/usr/bin/env bash
# Fails the build if the skeleton gains secrets, a broker URL, or a network client.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

fail() {
  echo "guardrail: $*" >&2
  exit 1
}

tracked_secrets="$(git ls-files | grep -E '(^|/)\.env$|\.pem$|\.p12$|(^|/)id_rsa$|(^|/)credentials\.json$' || true)"
if [[ -n "$tracked_secrets" ]]; then
  fail "tracked secret-like path:\n$tracked_secrets"
fi

broker_urls="$(git grep -I -n -E -i 'https?://[^[:space:]]*etoro' -- . ':!scripts/check-skeleton-guardrails.sh' || true)"
if [[ -n "$broker_urls" ]]; then
  fail "broker URL is not allowed:\n$broker_urls"
fi

private_keys="$(git grep -I -n -E 'BEGIN (RSA |OPENSSH |EC |PGP )?PRIVATE KEY' -- . || true)"
if [[ -n "$private_keys" ]]; then
  fail "private key material is not allowed"
fi

token_assignments="$(git grep -I -n -E '(api[_-]?key|api[_-]?secret|access[_-]?token|client[_-]?secret)[[:space:]]*[:=][[:space:]]*["'\''][^"'\'']{8,}["'\'']' -- . ':!scripts/check-skeleton-guardrails.sh' || true)"
if [[ -n "$token_assignments" ]]; then
  fail "possible secret assignment:\n$token_assignments"
fi

known_tokens="$(git grep -I -n -E 'AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{20,}' -- . || true)"
if [[ -n "$known_tokens" ]]; then
  fail "known token pattern is not allowed"
fi

postgres_in_backend="$(git grep -n 'POSTGRES_' -- backend || true)"
if [[ -n "$postgres_in_backend" ]]; then
  fail "backend must not read infra database settings:\n$postgres_in_backend"
fi

frontend_fetch="$(git grep -n -E '\bfetch\s*\(|XMLHttpRequest|axios' -- frontend/src ':!frontend/src/api/client.ts' || true)"
if [[ -n "$frontend_fetch" ]]; then
  fail "frontend may call the local API only from frontend/src/api/client.ts:\n$frontend_fetch"
fi
if ! grep -q 'method: "GET"' frontend/src/api/client.ts; then
  fail "the dashboard client must be read-only"
fi

grep -q 'operational-state: ${TRADING_OPERATIONAL_STATE:OBSERVATION}' backend/src/main/resources/application.yml \
  || fail "default operational state must be OBSERVATION"
grep -q 'enabled: ${TRADING_BROKER_ENABLED:false}' backend/src/main/resources/application.yml \
  || fail "broker must default to disabled"
grep -q "state = 'OBSERVATION'" infra/postgres/init/001_schemas.sql \
  || fail "database constraint must keep OBSERVATION"
grep -q '^backend/\.env$' .gitignore \
  || fail "backend/.env must be gitignored"
grep -q '^ETORO_DEMO_API_KEY=$' backend/.env.example \
  || fail "backend/.env.example must name ETORO_DEMO_API_KEY with an empty value"
grep -q '^ETORO_DEMO_USER_KEY=$' backend/.env.example \
  || fail "backend/.env.example must name ETORO_DEMO_USER_KEY with an empty value"
filled_demo_keys="$(git grep -n -E '^ETORO_DEMO_(API_KEY|USER_KEY)=[^[:space:]]' -- . || true)"
if [[ -n "$filled_demo_keys" ]]; then
  fail "demo key values must stay out of the repository:\n$filled_demo_keys"
fi

echo "guardrails ok"
