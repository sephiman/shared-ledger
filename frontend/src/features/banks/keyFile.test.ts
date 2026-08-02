import { describe, expect, it } from "vitest";
import {
  MAX_KEY_FILE_BYTES,
  keyFileSizeError,
  keyFileTextError,
  looksLikePkcs1,
  normalizeKey,
} from "./keyFile";

const BODY = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7";

const pem = `-----BEGIN PRIVATE KEY-----\n${BODY.slice(0, 26)}\n${BODY.slice(26)}\n-----END PRIVATE KEY-----\n`;

describe("normalizeKey", () => {
  it("reduces a downloaded PEM file and a one-line paste to the same body", () => {
    expect(normalizeKey(pem)).toBe(BODY);
    expect(normalizeKey(BODY)).toBe(BODY);
    expect(normalizeKey(`  ${BODY}  `)).toBe(BODY);
  });

  it("restores literal \\n escapes from a single-line env-style value", () => {
    expect(normalizeKey(`-----BEGIN PRIVATE KEY-----\\n${BODY}\\n-----END PRIVATE KEY-----`)).toBe(BODY);
  });

  it("strips PKCS#1 armour too, so the body still reaches the server for judging", () => {
    expect(normalizeKey(`-----BEGIN RSA PRIVATE KEY-----\n${BODY}\n-----END RSA PRIVATE KEY-----`)).toBe(BODY);
  });
});

describe("looksLikePkcs1", () => {
  it("spots the RSA header whatever the spacing or case", () => {
    expect(looksLikePkcs1("-----BEGIN RSA PRIVATE KEY-----")).toBe(true);
    expect(looksLikePkcs1("-----begin  rsa\tprivate key-----")).toBe(true);
  });

  it("leaves PKCS#8 and bare base64 alone", () => {
    expect(looksLikePkcs1(pem)).toBe(false);
    expect(looksLikePkcs1(BODY)).toBe(false);
  });
});

describe("keyFileSizeError", () => {
  it("accepts a real key file and rejects anything past the cap", () => {
    expect(keyFileSizeError(3 * 1024)).toBeNull();
    expect(keyFileSizeError(MAX_KEY_FILE_BYTES)).toBeNull();
    expect(keyFileSizeError(MAX_KEY_FILE_BYTES + 1)).toBe("too_large");
  });
});

describe("keyFileTextError", () => {
  it("accepts a PEM file and a bare base64 file", () => {
    expect(keyFileTextError(pem)).toBeNull();
    expect(keyFileTextError(BODY)).toBeNull();
  });

  it("reports a binary file — a .p12 bundle decoded as text", () => {
    // What TextDecoder yields for DER / PKCS#12 bytes: NUL plus replacement characters.
    expect(keyFileTextError("0\u0000\u0002\u0001\u0003")).toBe("binary");
    expect(keyFileTextError(`${BODY}\uFFFD`)).toBe("binary");
  });

  it("reports a file with no key in it", () => {
    expect(keyFileTextError("")).toBe("empty");
    expect(keyFileTextError("   \n\t ")).toBe("empty");
    expect(keyFileTextError("-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----")).toBe("empty");
  });
});
