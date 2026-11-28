import { readDemoOverview } from "../../../lib/demo-read";
import { serverFetch } from "../../../lib/server-fetch";

export const dynamic = "force-dynamic";
export const fetchCache = "force-no-store";

export async function GET() {
  const overview = await readDemoOverview({
    env: process.env,
    fetchImpl: serverFetch,
  });
  return Response.json(overview, {
    headers: { "cache-control": "no-store" },
  });
}
