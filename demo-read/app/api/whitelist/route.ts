import { readWhitelist } from "../../../lib/whitelist";
import { serverFetch } from "../../../lib/server-fetch";

export const dynamic = "force-dynamic";
export const fetchCache = "force-no-store";

export async function GET() {
  const cards = await readWhitelist({
    env: process.env,
    fetchImpl: serverFetch,
  });
  return Response.json(cards, {
    headers: { "cache-control": "no-store" },
  });
}
