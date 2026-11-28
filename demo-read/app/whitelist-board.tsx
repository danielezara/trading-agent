"use client";

import { useEffect, useState } from "react";
import { type ProgramBook } from "../lib/program-book";
import { mergeCards, portfolioNote, WHITELIST, type Candle, type CryptoCard } from "../lib/whitelist";

const POLL_MS = 5_000;

export function WhitelistBoard({ book }: { book: ProgramBook }) {
  const [cards, setCards] = useState<CryptoCard[] | null>(null);
  const [refreshError, setRefreshError] = useState(false);
  const [seenAt, setSeenAt] = useState<string | null>(null);

  useEffect(() => {
    let stopped = false;
    let pending = false;
    async function refresh() {
      if (pending) {
        return;
      }
      pending = true;
      try {
        const response = await fetch("/api/whitelist", { cache: "no-store" });
        if (!response.ok) {
          throw new Error("refresh failed");
        }
        const next = (await response.json()) as CryptoCard[];
        if (!stopped && Array.isArray(next) && next.length === WHITELIST.length) {
          setCards((current) => mergeCards(current, next));
          setSeenAt(new Date().toISOString());
          setRefreshError(false);
        } else if (!stopped) {
          setRefreshError(true);
        }
      } catch {
        if (!stopped) {
          setRefreshError(true);
          setCards((current) => current?.map((card) => card.candles.length > 0 ? { ...card, stale: true, reason: "Ultimo disponibile" } : card) ?? current);
        }
      } finally {
        pending = false;
      }
    }
    void refresh();
    const timer = setInterval(() => {
      void refresh();
    }, POLL_MS);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, []);

  const shown = cards ?? WHITELIST.map((ticker) => ({ ticker, pair: null, reason: "Lettura in corso", candles: [] as Candle[], price: null, stale: false, quotedAt: null }));
  const stale = shown.some((card) => card.stale);
  return (
    <section className="cryptos">
      <header className="bot-head">
        <div>
          <p className="eyebrow">Whitelist</p>
          <h2>Le 20 crypto</h2>
        </div>
        <p className="asof">Aggiornamento indipendente ogni 5 secondi{seenAt ? ` · ${rome(seenAt)}` : ""}{stale ? " · ultimo disponibile" : ""}{refreshError ? " · aggiornamento non riuscito" : ""}</p>
      </header>
      <div className="crypto-grid">
        {shown.map((card) => (
          <CryptoSection key={card.ticker} card={card} book={book} />
        ))}
      </div>
    </section>
  );
}

function CryptoSection({ card, book }: { card: CryptoCard; book: ProgramBook }) {
  const note = card.reason === "Lettura in corso"
    ? { membership: "Lettura in corso", performance: "non disponibile" }
    : portfolioNote(card.pair, book.positions);
  return (
    <article className="card crypto">
      <div className="card-top">
        <p className="symbol">{card.ticker}</p>
        <p className="order">{card.pair ? `${card.pair.symbol} #${card.pair.instrumentId}` : card.reason ?? "Coppia non disponibile"}</p>
      </div>
      {card.candles.length > 0 ? <CandleChart candles={card.candles} /> : card.pair ? <p className="missing">{card.reason ?? "Candele non disponibili"}</p> : null}
      <dl className="facts">
        <div><dt>Prezzo</dt><dd>{card.price ?? "non disponibile"}{card.stale ? ` · ultimo disponibile${card.quotedAt ? ` ${rome(card.quotedAt)}` : ""}` : ""}</dd></div>
        <div><dt>Portafoglio</dt><dd>{note.membership}</dd></div>
        <div><dt>Rendimento</dt><dd className={toneOf(note.performance)}>{note.performance}</dd></div>
      </dl>
    </article>
  );
}

function CandleChart({ candles }: { candles: Candle[] }) {
  const width = 280;
  const height = 92;
  const high = Math.max(...candles.map((candle) => candle.high));
  const low = Math.min(...candles.map((candle) => candle.low));
  const span = high - low || 1;
  const slot = width / candles.length;
  const y = (price: number) => ((high - price) / span) * (height - 8) + 4;
  return (
    <svg className="chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Candele da 5 minuti">
      {candles.map((candle, index) => {
        const x = index * slot + slot / 2;
        const up = candle.close >= candle.open;
        const top = y(Math.max(candle.open, candle.close));
        const bottom = y(Math.min(candle.open, candle.close));
        const body = Math.max(1, bottom - top);
        return (
          <g key={candle.start} className={`${up ? "up" : "down"}${candle.forming ? " forming" : ""}`}>
            <line x1={x} x2={x} y1={y(candle.high)} y2={y(candle.low)} />
            <rect x={x - Math.max(1, slot * 0.28)} y={top} width={Math.max(2, slot * 0.56)} height={body} />
          </g>
        );
      })}
    </svg>
  );
}

function rome(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("it-IT", {
    timeZone: "Europe/Rome",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    day: "2-digit",
    month: "2-digit",
  }).format(date);
}

function toneOf(performance: string): string {
  if (performance === "non disponibile") {
    return "missing";
  }
  if (performance.startsWith("+")) {
    return "up";
  }
  if (performance.startsWith("-")) {
    return "down";
  }
  return "flat";
}
