/**
 * The Enable Banking private key as the credentials card accepts it: pasted text or an uploaded
 * file, both reduced to the same base64 body before anything is sent. The server normalizes again
 * and has the final say; these only keep the field honest about what will be stored.
 */

/** A key file is 2–4 KB; anything past this is not one. */
export const MAX_KEY_FILE_BYTES = 32 * 1024;

export type KeyFileError = "too_large" | "binary" | "empty";

/** Strips literal `\n` escapes, PEM armour and whitespace, so a key file and a one-line blob match. */
export function normalizeKey(raw: string): string {
  return raw
    .replace(/\\n/g, "\n")
    .replace(/-----(BEGIN|END)[^-]*-----/g, "")
    .replace(/\s/g, "");
}

/** PKCS#1 is the common wrong key; flag it as soon as it is typed or dropped, not on save. */
export function looksLikePkcs1(raw: string): boolean {
  return /BEGIN\s+RSA\s+PRIVATE\s+KEY/i.test(raw);
}

/** Checked before reading, so an enormous file is never pulled into memory. */
export function keyFileSizeError(bytes: number): KeyFileError | null {
  return bytes > MAX_KEY_FILE_BYTES ? "too_large" : null;
}

/**
 * Decoding a binary file (a `.p12`/`.pfx` bundle, or raw DER) as text yields NUL and replacement
 * characters — the signal that the user grabbed the wrong file rather than a malformed key.
 */
export function keyFileTextError(text: string): KeyFileError | null {
  if (/[\u0000\uFFFD]/.test(text)) return "binary";
  return normalizeKey(text) === "" ? "empty" : null;
}
