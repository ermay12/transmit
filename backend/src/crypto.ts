// Keyed hashes for lookups and AES-GCM for the one value we must read back
// (the purchase token). Both keys are derived from the DATA_SECRET secret.

const enc = new TextEncoder();
const dec = new TextDecoder();

export interface DataKeys {
  hmac: CryptoKey;
  aes: CryptoKey;
}

let cached: { secret: string; keys: Promise<DataKeys> } | undefined;

export function dataKeys(secret: string): Promise<DataKeys> {
  if (!secret || secret.length < 32) throw new Error("DATA_SECRET must be at least 32 characters");
  if (cached?.secret === secret) return cached.keys;
  const keys = (async () => {
    const base = await crypto.subtle.importKey("raw", enc.encode(secret), "HKDF", false, ["deriveKey"]);
    type DeriveArgs = Parameters<SubtleCrypto["deriveKey"]>;
    const derive = (info: string, alg: DeriveArgs[2], usages: DeriveArgs[4]) =>
      crypto.subtle.deriveKey({ name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: enc.encode(info) }, base, alg, false, usages);
    return {
      hmac: await derive("transmit-hmac-v1", { name: "HMAC", hash: "SHA-256", length: 256 }, ["sign"]),
      aes: await derive("transmit-aes-v1", { name: "AES-GCM", length: 256 }, ["encrypt", "decrypt"]),
    };
  })();
  cached = { secret, keys };
  return keys;
}

export function hex(bytes: ArrayBuffer | Uint8Array): string {
  return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function b64(bytes: ArrayBuffer | Uint8Array): string {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s);
}

export function unb64(text: string): Uint8Array {
  const s = atob(text);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

export function b64url(bytes: ArrayBuffer | Uint8Array | string): string {
  const raw = typeof bytes === "string" ? enc.encode(bytes) : bytes;
  return b64(raw).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function sha256Hex(text: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", enc.encode(text)));
}

/** Keyed hash used as a database key: HMAC-SHA256(purpose + ":" + value), hex. */
export async function keyedHash(keys: DataKeys, purpose: "device" | "purchase" | "ip", value: string): Promise<string> {
  return hex(await crypto.subtle.sign("HMAC", keys.hmac, enc.encode(`${purpose}:${value}`)));
}

export async function encrypt(keys: DataKeys, plaintext: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, keys.aes, enc.encode(plaintext)));
  const out = new Uint8Array(iv.length + ct.length);
  out.set(iv);
  out.set(ct, iv.length);
  return b64(out);
}

export async function decrypt(keys: DataKeys, sealed: string): Promise<string> {
  const raw = unb64(sealed);
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: raw.slice(0, 12) }, keys.aes, raw.slice(12));
  return dec.decode(pt);
}

/** What the app must put in the Integrity requestHash for a trial request. */
export function trialRequestHash(androidId: string): Promise<string> {
  return sha256Hex(`transmit-trial-v1:${androidId}`);
}
