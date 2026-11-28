import { readProgramBook } from "../../../lib/demo-read";
import { holdBook, type ProgramBook } from "../../../lib/program-book";
import { serverFetch } from "../../../lib/server-fetch";

export const dynamic = "force-dynamic";
export const fetchCache = "force-no-store";

const FRESH_MS = 20_000;
let cached: { at: number; book: ProgramBook } | null = null;
let inflight: Promise<ProgramBook> | null = null;

export async function GET() {
  const now = Date.now();
  if (cached && now - cached.at < FRESH_MS) {
    return Response.json({ ...cached.book, stale: false }, {
      headers: { "cache-control": "no-store" },
    });
  }
  if (!inflight) {
    inflight = readProgramBook({
      env: process.env,
      fetchImpl: serverFetch,
    }).finally(() => {
      inflight = null;
    });
  }
  const next = await inflight;
  const held = holdBook(cached?.book ?? null, next);
  cached = { at: now, book: held.book };
  return Response.json({ ...held.book, stale: held.stale }, {
    headers: { "cache-control": "no-store" },
  });
}
