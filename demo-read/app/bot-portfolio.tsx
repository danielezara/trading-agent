"use client";

import { useEffect, useRef, useState } from "react";
import { WhitelistBoard } from "./whitelist-board";
import {
  BUDGET_EUR_MINOR,
  type ProgramBook,
  type ProgramPosition,
  type Tick,
  changeBps,
  formatBps,
  formatMinor,
  formatSignedMinor,
  missingValueSentence,
  tick,
} from "../lib/program-book";

const POLL_MS = 15_000;

export function BotPortfolio({ initial }: { initial: ProgramBook }) {
  const [book, setBook] = useState(initial);
  const [liveTicks, setLiveTicks] = useState<Record<string, Tick | null>>({});
  const [refreshError, setRefreshError] = useState(false);
  const bookRef = useRef(initial);

  useEffect(() => {
    let stopped = false;
    async function refresh() {
      try {
        const response = await fetch("/api/bot-portfolio", { cache: "no-store" });
        if (!response.ok) {
          throw new Error("refresh failed");
        }
        const next = (await response.json()) as ProgramBook;
        if (stopped) {
          return;
        }
        const current = bookRef.current;
        const ticks: Record<string, Tick | null> = {};
        for (const position of next.positions ?? []) {
          const prior = current.positions?.find((row) => row.orderId === position.orderId)?.bid ?? null;
          ticks[position.orderId] = tick(prior, position.bid);
        }
        if (next.benchmark.resolved) {
          ticks.benchmark = tick(current.benchmark.bid, next.benchmark.bid);
        }
        bookRef.current = next;
        setLiveTicks(ticks);
        setBook(next);
        setRefreshError(false);
      } catch {
        if (!stopped) {
          setRefreshError(true);
        }
      }
    }
    const timer = setInterval(() => {
      void refresh();
    }, POLL_MS);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, []);

  const lossClass = book.lossEurMinor !== null && book.lossEurMinor > 0 ? "down" : "flat";
  return (
    <section className="bot">
      <header className="bot-head">
        <div>
          <p className="eyebrow">Portafoglio bot</p>
          <h2>Dentro i 500 EUR</h2>
        </div>
        <p className="asof">
          Prezzi eToro
          {book.readAt ? ` · ${rome(book.readAt)}` : ""}
          {refreshError ? " · aggiornamento non riuscito" : ""}
        </p>
      </header>
      <div className="metrics">
        <Metric label="Valore" value={formatMinor(book.valueEurMinor, "EUR")} detail={valueDetail(book)} tone={book.missingValues.length > 0 ? "flat" : toneOf(book.changeEurMinor)} />
        <Metric label="Variazione" value={book.missingValues.length > 0 ? "non disponibile" : formatSignedMinor(book.changeEurMinor, "EUR")} detail={book.missingValues.length > 0 ? missingValueSentence(book.missingValues) ?? "non disponibile" : formatSignedMinor(book.changeUsdMinor, "USD")} tone={book.missingValues.length > 0 ? "missing" : toneOf(book.changeEurMinor)} />
        <Metric label="Perdita sul budget" value={book.missingValues.length > 0 ? "non disponibile" : formatMinor(book.lossEurMinor, "EUR")} detail={book.missingValues.length > 0 ? missingValueSentence(book.missingValues) ?? "non disponibile" : book.lossBpsOfBudget === null ? "non disponibile" : `${formatBps(book.lossBpsOfBudget)} di ${formatMinor(BUDGET_EUR_MINOR, "EUR")}`} tone={book.missingValues.length > 0 ? "missing" : lossClass} />
      </div>
      {book.positions === null ? <p className="missing">Portafoglio non disponibile</p> : null}
      <Composition book={book} />
      <BenchmarkCard book={book} live={liveTicks.benchmark ?? null} />
      <WhitelistBoard book={book} />
      {book.positions === null ? null : book.positions.length === 0 ? <p className="missing">Nessuna posizione del programma</p> : book.positions.map((position) => (
        <PositionCard key={position.orderId} position={position} live={liveTicks[position.orderId] ?? null} />
      ))}
    </section>
  );
}

function Composition({ book }: { book: ProgramBook }) {
  if (book.positions === null) {
    return <p className="missing">Composizione non disponibile</p>;
  }
  if (book.positions.length === 0) {
    return (
      <article className="card">
        <p className="symbol">Composizione</p>
        <p className="missing">Nessuna posizione del programma</p>
      </article>
    );
  }
  const sharesKnown = book.missingValues.length === 0
    && book.valueEurMinor !== null
    && book.valueEurMinor > 0
    && book.positions.every((position) => position.valueEurMinor !== null);
  return (
    <article className="card">
      <p className="symbol">Composizione</p>
      <ul className="composition">
        {book.positions.map((position) => (
          <li key={position.orderId}>
            <span>{position.symbol || position.name || `Strumento ${position.instrumentId}`}</span>
            <span>{formatMinor(position.valueEurMinor, "EUR")}</span>
            <span>{sharesKnown ? shareText(position.valueEurMinor, book.valueEurMinor) : "quota non disponibile"}</span>
          </li>
        ))}
      </ul>
    </article>
  );
}

function shareText(part: number | null, total: number | null): string {
  if (part === null || total === null || total <= 0) {
    return "quota non disponibile";
  }
  const bps = Math.round((part / total) * 10_000);
  if (!Number.isSafeInteger(bps)) {
    return "quota non disponibile";
  }
  return formatBps(bps).replace(/^\+/, "");
}

function PositionCard({ position, live }: { position: ProgramPosition; live: Tick | null }) {
  const move = changeBps(position.openRate, position.bid);
  return (
    <article className="card">
      <div className="card-top">
        <div>
          <p className="symbol">{position.symbol || position.name || "Nome non disponibile"}</p>
          <p className="name">{position.symbol && position.name ? position.name : position.name ? `Strumento ${position.instrumentId}` : "Nome non disponibile"}</p>
        </div>
        <p className="order">Ordine {position.orderId}</p>
      </div>
      <Quote price={position.bid} since={position.sinceOpen} live={live} time={position.quoteTime} />
      <dl className="facts">
        <div><dt>Ingresso</dt><dd>{position.openRate ?? "non disponibile"}</dd></div>
        <div><dt>Dal prezzo di ingresso</dt><dd className={toneOfBps(move)}>{formatBps(move)}</dd></div>
        <div><dt>Valore</dt><dd>{formatMinor(position.valueEurMinor, "EUR")}</dd></div>
        <div><dt>Variazione</dt><dd className={toneOf(position.changeEurMinor)}>{formatSignedMinor(position.changeEurMinor, "EUR")}</dd></div>
        <div><dt>Ultimo movimento</dt><dd>{live === "up" ? "in rialzo" : live === "down" ? "in calo" : live === "flat" ? "invariata" : "in attesa del prossimo prezzo"}</dd></div>
      </dl>
    </article>
  );
}

function BenchmarkCard({ book, live }: { book: ProgramBook; live: Tick | null }) {
  const benchmark = book.benchmark;
  const botBps = bookMove(book);
  return (
    <article className="card">
      <div className="card-top">
        <div>
          <p className="symbol">Confronto ETF World</p>
          <p className="name">{benchmark.resolved ? benchmark.name ?? "Nome non disponibile" : "Confronto non disponibile"}</p>
        </div>
      </div>
      {benchmark.resolved ? (
        <>
          <Quote price={benchmark.bid} since={benchmark.sinceReference} live={live} time={benchmark.quoteTime} />
          <dl className="facts">
            <div><dt>Dal momento dell&apos;ingresso</dt><dd className={toneOfBps(benchmark.changeBps)}>{benchmark.referencePrice ? formatBps(benchmark.changeBps) : "non disponibile"}</dd></div>
            <div><dt>Posizione del bot</dt><dd className={toneOfBps(botBps)}>{formatBps(botBps)}</dd></div>
            <div><dt>Strumento</dt><dd>{benchmark.instrumentId}</dd></div>
          </dl>
        </>
      ) : (
        <p className="missing">Confronto non disponibile.</p>
      )}
    </article>
  );
}

function Quote({ price, since, live, time }: { price: string | null; since: Tick | null; live: Tick | null; time: string | null }) {
  const shown = live ?? since;
  return (
    <p className={`quote ${shown ?? "missing"}`}>
      <span>{price ?? "Quotazione non disponibile"}</span>
      {price ? <Mark tick={shown} /> : null}
      {time ? <small>{rome(time)}</small> : null}
    </p>
  );
}

function Mark({ tick: direction }: { tick: Tick | null }) {
  if (direction === "up") {
    return <em>in rialzo</em>;
  }
  if (direction === "down") {
    return <em>in calo</em>;
  }
  if (direction === "flat") {
    return <em>invariata</em>;
  }
  return null;
}

function Metric({ label, value, detail, tone }: { label: string; value: string; detail: string; tone: string }) {
  return (
    <article className={`card metric ${tone}`}>
      <p>{label}</p>
      <strong>{value}</strong>
      <span>{detail}</span>
    </article>
  );
}

function valueDetail(book: ProgramBook): string {
  const missing = missingValueSentence(book.missingValues);
  const usd = formatMinor(book.valueUsdMinor, "USD");
  if (!missing) {
    return usd;
  }
  return book.valueUsdMinor === null ? missing : `${missing}. Sommati ${usd}`;
}

function bookMove(book: ProgramBook): number | null {
  if (book.missingValues.length > 0 || book.valueUsdMinor === null || book.changeUsdMinor === null) {
    return null;
  }
  const cost = book.valueUsdMinor - book.changeUsdMinor;
  if (cost <= 0) {
    return null;
  }
  const bps = Math.round((book.changeUsdMinor / cost) * 10_000);
  return Number.isSafeInteger(bps) ? bps : null;
}

function toneOf(minor: number | null): string {
  if (minor === null) {
    return "missing";
  }
  if (minor > 0) {
    return "up";
  }
  if (minor < 0) {
    return "down";
  }
  return "flat";
}

function toneOfBps(bps: number | null): string {
  return toneOf(bps);
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
