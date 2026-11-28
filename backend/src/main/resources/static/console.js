async function refreshWhitelist() {
  const grid = document.getElementById("crypto-grid");
  const asof = document.getElementById("whitelist-asof");
  try {
    const response = await fetch("/api/v1/whitelist", { cache: "no-store" });
    if (!response.ok) {
      throw new Error("whitelist failed");
    }
    const cards = await response.json();
    asof.textContent = "Aggiornamento indipendente · " + new Date().toLocaleString("it-IT");
    grid.innerHTML = cards.map(renderCard).join("");
  } catch (error) {
    asof.textContent = "Aggiornamento non riuscito";
  }
}

function renderCard(card) {
  const title = card.symbol && card.instrument_id
    ? `${card.symbol} #${card.instrument_id}`
    : (card.reason || "non disponibile");
  const chart = card.candles && card.candles.length
    ? renderChart(card.candles)
    : `<p class="muted">${card.reason || "Candele non disponibili"}</p>`;
  const price = card.price || "non disponibile";
  const stale = card.stale ? " · ultimo disponibile" : "";
  return `<article class="card">
    <p class="symbol">${card.ticker}</p>
    <p class="order">${title}</p>
    ${chart}
    <p>Prezzo ${price}${stale}</p>
  </article>`;
}

function renderChart(candles) {
  const width = 280;
  const height = 92;
  const high = Math.max(...candles.map((c) => c.high));
  const low = Math.min(...candles.map((c) => c.low));
  const span = high - low || 1;
  const slot = width / candles.length;
  const y = (price) => ((high - price) / span) * (height - 8) + 4;
  const bars = candles.map((candle, index) => {
    const x = index * slot + slot / 2;
    const up = candle.close >= candle.open;
    const top = y(Math.max(candle.open, candle.close));
    const bottom = y(Math.min(candle.open, candle.close));
    const body = Math.max(1, bottom - top);
    const cls = `${up ? "up" : "down"}${candle.forming ? " forming" : ""}`;
    return `<g class="${cls}">
      <line x1="${x}" x2="${x}" y1="${y(candle.high)}" y2="${y(candle.low)}"></line>
      <rect x="${x - Math.max(1, slot * 0.28)}" y="${top}" width="${Math.max(2, slot * 0.56)}" height="${body}"></rect>
    </g>`;
  }).join("");
  return `<svg class="chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="Candele da 5 minuti">${bars}</svg>`;
}

async function closePosition(positionId) {
  const result = document.getElementById("trade-result");
  result.hidden = false;
  result.textContent = "Chiusura in corso…";
  const response = await fetch(`/api/v1/demo/orders/close/${encodeURIComponent(positionId)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
  });
  const body = await response.json();
  result.textContent = `${body.status}: ${body.reason}${body.order_id ? " · " + body.order_id : ""}`;
  setTimeout(() => location.reload(), 800);
}

document.getElementById("open-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const payload = {
    instrument_id: form.instrumentId.value.trim(),
    amount: form.amount.value.trim(),
    leverage: 1,
  };
  const result = document.getElementById("trade-result");
  result.hidden = false;
  result.textContent = "Invio ordine…";
  const response = await fetch("/api/v1/demo/orders/open", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const body = await response.json();
  result.textContent = `${body.status}: ${body.reason}${body.order_id ? " · " + body.order_id : ""}`;
});

refreshWhitelist();
setInterval(refreshWhitelist, 5000);
