import { readProgramBook } from "../../../lib/demo-read";
import { serverFetch } from "../../../lib/server-fetch";

export const dynamic = "force-dynamic";
export const fetchCache = "force-no-store";

export async function GET() {
  const book = await readProgramBook({
    env: process.env,
    fetchImpl: serverFetch,
  });
  return Response.json(book, {
    headers: { "cache-control": "no-store" },
  });
}
