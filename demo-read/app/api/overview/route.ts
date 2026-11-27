import { readDemoOverview } from "../../../lib/demo-read";

export const dynamic = "force-dynamic";

export async function GET() {
  const overview = await readDemoOverview({
    env: process.env,
    fetchImpl: fetch,
  });
  return Response.json(overview, {
    headers: { "cache-control": "no-store" },
  });
}
