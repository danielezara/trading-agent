export async function getJson<T>(path: string): Promise<T> {
  if (path.includes("://") || path.startsWith("//") || !path.startsWith("/") || path.includes("..")) {
    throw new Error("Solo un percorso relativo locale è consentito");
  }
  if (!path.startsWith("/api/") && !path.startsWith("/health/")) {
    throw new Error("Percorso API non consentito");
  }
  const response = await fetch(path, {
    method: "GET",
    headers: { Accept: "application/json" },
    credentials: "omit",
    cache: "no-store",
  });
  if (!response.ok) {
    throw new Error(`Lettura API non riuscita (${response.status})`);
  }
  return response.json() as Promise<T>;
}
