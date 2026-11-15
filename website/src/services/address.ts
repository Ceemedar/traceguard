const CACHE_KEY = "traceguard-reverse-addresses-v2";
const MAX_CACHE_ENTRIES = 40;
const MIN_REQUEST_GAP_MS = 1_100;

type CachedAddresses = Record<string, string | null>;
const pendingLookups = new Map<string, Promise<string | null>>();
let requestQueue: Promise<unknown> = Promise.resolve();
let lastRequestAt = 0;

function readCache(): CachedAddresses {
  try {
    const raw = sessionStorage.getItem(CACHE_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? parsed as CachedAddresses
      : {};
  } catch {
    return {};
  }
}

function writeCache(cache: CachedAddresses) {
  try {
    sessionStorage.setItem(CACHE_KEY, JSON.stringify(cache));
  } catch {
    // Address display still works for this page if browser storage is unavailable.
  }
}

function coordinateKey(latitude: number, longitude: number) {
  return `${latitude.toFixed(5)},${longitude.toFixed(5)}`;
}

function fetchAtLimitedRate(url: URL) {
  const request = requestQueue.then(async () => {
    const waitMs = Math.max(0, lastRequestAt + MIN_REQUEST_GAP_MS - Date.now());
    if (waitMs) await new Promise((resolve) => window.setTimeout(resolve, waitMs));
    lastRequestAt = Date.now();
    return fetch(url, { headers: { Accept: "application/json" } });
  });
  requestQueue = request.then(() => undefined, () => undefined);
  return request;
}

function field(address: Record<string, unknown>, ...names: string[]) {
  for (const name of names) {
    const value = address[name];
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return null;
}

function formatStreetAddress(result: unknown): string | null {
  if (!result || typeof result !== "object") return null;
  const data = result as { address?: unknown; display_name?: unknown };
  const address = data.address && typeof data.address === "object"
    ? data.address as Record<string, unknown>
    : {};
  const houseNumber = field(address, "house_number");
  const street = field(address, "road", "pedestrian", "residential", "street", "path");
  const streetLine = [houseNumber, street].filter(Boolean).join(" ");
  const area = field(address, "suburb", "neighbourhood", "quarter", "city_district", "hamlet");
  const locality = field(address, "city", "town", "village", "municipality", "county");
  const parts = [streetLine || street || houseNumber, area, locality]
    .filter((part): part is string => Boolean(part));
  const uniqueParts = parts.filter((part, index) =>
    parts.findIndex((candidate) => candidate.toLocaleLowerCase() === part.toLocaleLowerCase()) === index,
  );
  if (uniqueParts.length) return uniqueParts.join(", ");

  if (typeof data.display_name === "string") {
    const conciseFallback = data.display_name.split(",").map((part) => part.trim()).filter(Boolean).slice(0, 3);
    return conciseFallback.length ? conciseFallback.join(", ") : null;
  }
  return null;
}

export async function reverseGeocode(latitude: number, longitude: number): Promise<string | null> {
  const key = coordinateKey(latitude, longitude);
  const cache = readCache();
  if (Object.prototype.hasOwnProperty.call(cache, key)) return cache[key];
  const pending = pendingLookups.get(key);
  if (pending) return pending;

  const url = new URL("https://nominatim.openstreetmap.org/reverse");
  url.searchParams.set("format", "jsonv2");
  url.searchParams.set("lat", latitude.toString());
  url.searchParams.set("lon", longitude.toString());
  url.searchParams.set("zoom", "18");
  url.searchParams.set("addressdetails", "1");

  const lookup = (async () => {
    try {
      const response = await fetchAtLimitedRate(url);
      if (!response.ok) throw new Error(`Address lookup failed (${response.status}).`);
      const result: unknown = await response.json();
      const address = formatStreetAddress(result);
      const updated = { ...readCache(), [key]: address };
      const keys = Object.keys(updated);
      if (keys.length > MAX_CACHE_ENTRIES) {
        for (const staleKey of keys.slice(0, keys.length - MAX_CACHE_ENTRIES)) delete updated[staleKey];
      }
      writeCache(updated);
      return address;
    } catch {
      return null;
    } finally {
      pendingLookups.delete(key);
    }
  })();
  pendingLookups.set(key, lookup);
  return lookup;
}
