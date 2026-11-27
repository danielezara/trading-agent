const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

export function assertSkeletonApiBase(baseUrl: string): void {
  if (baseUrl.trim() === "") {
    return;
  }
  let parsed: URL;
  try {
    parsed = new URL(baseUrl);
  } catch {
    throw new Error("Base API non valida");
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error("Protocollo API non consentito");
  }
  if (!LOCAL_HOSTS.has(parsed.hostname)) {
    throw new Error("Lo scheletro accetta solo un'API locale");
  }
  if (parsed.username !== "" || parsed.password !== "") {
    throw new Error("La base API non può contenere credenziali");
  }
  if (/etoro/i.test(parsed.href)) {
    throw new Error("Endpoint broker non consentito");
  }
}
