import axios from "axios";
import express, { type RequestHandler } from "express";
import type { Skill } from "ask-sdk-core";
import { fromBER } from "asn1js";
import {
  Certificate,
  CertificateChainValidationEngine,
  CryptoEngine,
} from "pkijs";
import { constants, verify, webcrypto, X509Certificate } from "node:crypto";
import { rootCertificates } from "node:tls";

type RequestEnvelope = Parameters<Skill["invoke"]>[0];
type VerificationHeaders = { certificateUrl?: string; signature?: string };
type VerifierOptions = {
  trustedCertificates?: readonly string[];
  fetchCertificateChain?: (url: string) => Promise<string>;
  now?: () => number;
};
export type AlexaRequestVerifier = (
  body: Buffer,
  headers: VerificationHeaders,
  skillId: string
) => Promise<RequestEnvelope>;

const MAX_CERTIFICATE_BYTES = 64 * 1024;
const MAX_CACHE_ENTRIES = 64;
const CACHE_TTL_MS = 5 * 60_000;
const REQUEST_TTL_MS = 150_000;
// Critical extensions whose constraints are enforced by this verifier or the
// PKIjs path validator. Recognizing an ASN.1 schema alone is insufficient.
const SUPPORTED_CRITICAL_EXTENSIONS = new Set([
  "2.5.29.15",
  "2.5.29.17",
  "2.5.29.19",
  "2.5.29.30",
  "2.5.29.32",
  "2.5.29.33",
  "2.5.29.36",
  "2.5.29.54",
]);
// Pass an explicit engine instead of changing PKIjs's process-wide engine.
const cryptoEngine = new CryptoEngine({
  crypto: webcrypto as unknown as Crypto,
});

function reject(): never {
  throw new Error("invalid_alexa_request");
}

function certificateUrl(value: string | undefined): string {
  if (!value || value.length > 2048 || /[\s\\%]/.test(value)) reject();
  const url = new URL(value);
  if (
    url.protocol !== "https:" ||
    url.hostname !== "s3.amazonaws.com" ||
    (url.port && url.port !== "443") ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    !url.pathname.startsWith("/echo.api/") ||
    url.pathname === "/echo.api/"
  )
    reject();
  return url.href;
}

async function fetchCertificateChain(url: string): Promise<string> {
  const response = await axios.get<string>(url, {
    responseType: "text",
    transformResponse: data => data,
    timeout: 5000,
    maxRedirects: 0,
    maxContentLength: MAX_CERTIFICATE_BYTES,
    validateStatus: status => status === 200,
  });
  return response.data;
}

function parseCertificate(certificate: X509Certificate): Certificate {
  const decoded = fromBER(certificate.raw);
  if (decoded.offset !== certificate.raw.length) reject();
  return new Certificate({ schema: decoded.result });
}

function parseChain(pem: string): X509Certificate[] {
  if (typeof pem !== "string" || Buffer.byteLength(pem) > MAX_CERTIFICATE_BYTES)
    reject();
  const blocks = pem.match(
    /-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g
  );
  if (
    !blocks ||
    blocks.length > 8 ||
    pem
      .replace(
        /-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g,
        ""
      )
      .trim()
  )
    reject();
  return blocks.map(block => new X509Certificate(block));
}

function checkEnvelope(
  value: unknown,
  skillId: string,
  now: number
): RequestEnvelope {
  const envelope = value as RequestEnvelope | null;
  if (
    !envelope ||
    typeof envelope !== "object" ||
    !skillId ||
    envelope.version !== "1.0" ||
    typeof envelope.request?.type !== "string"
  )
    reject();
  const timestamp = envelope.request.timestamp;
  const time = typeof timestamp === "string" ? Date.parse(timestamp) : NaN;
  if (!Number.isFinite(time) || Math.abs(now - time) > REQUEST_TTL_MS) reject();
  const applicationIds = [
    envelope.context?.System?.application?.applicationId,
    envelope.session?.application?.applicationId,
  ].filter(id => id !== undefined);
  if (!applicationIds.length || applicationIds.some(id => id !== skillId))
    reject();
  return envelope;
}

/** Verify Amazon's web-service contract before dispatching any skill handler. */
export function createAlexaRequestVerifier(
  options: VerifierOptions = {}
): AlexaRequestVerifier {
  const clock = options.now ?? Date.now;
  const fetchChain = options.fetchCertificateChain ?? fetchCertificateChain;
  // TLS transport may trust an enterprise proxy; signing trust remains the public
  // Node root set, independent of NODE_EXTRA_CA_CERTS and environment CA overrides.
  let trustedCerts: Certificate[] | undefined;
  const cache = new Map<
    string,
    { certificate: X509Certificate; expires: number }
  >();

  async function signingCertificate(url: string): Promise<X509Certificate> {
    const cached = cache.get(url);
    if (cached && cached.expires > clock()) return cached.certificate;
    cache.delete(url);
    // Amazon sends the signing leaf first, followed by its intermediates.
    const chain = parseChain(await fetchChain(url));
    const leaf = chain[0];
    if (
      leaf.ca ||
      leaf.publicKey.asymmetricKeyType !== "rsa" ||
      leaf.checkHost("echo-api.amazon.com", {
        wildcards: false,
        subject: "never",
      }) !== "echo-api.amazon.com"
    )
      reject();
    trustedCerts ??= (options.trustedCertificates ?? rootCertificates).map(
      pem => parseCertificate(new X509Certificate(pem))
    );
    const certificates = chain.map(parseCertificate);
    const keyUsage = certificates[0].extensions?.find(
      ext => ext.extnID === "2.5.29.15"
    );
    if (keyUsage && !(keyUsage.parsedValue?.valueBlock?.valueHexView[0] & 0x80))
      reject();
    let issuerLookups = 0;
    const validation = await new CertificateChainValidationEngine({
      trustedCerts,
      // PKIjs expects the target leaf last and builds a path to a trusted root.
      certs: [...certificates].reverse(),
      checkDate: new Date(clock()),
      findIssuer: (certificate, engine, crypto) => {
        // Bound path exploration even for malformed or cyclic input chains.
        if (++issuerLookups > 64) reject();
        return engine.defaultFindIssuer(certificate, engine, crypto);
      },
    }).verify({}, cryptoEngine);
    if (!validation.result || !validation.certificatePath) reject();
    if (
      !Buffer.from(validation.certificatePath[0].tbsView).equals(
        Buffer.from(certificates[0].tbsView)
      )
    )
      reject();
    for (let index = 0; index < validation.certificatePath.length; index++) {
      const cert = validation.certificatePath[index];
      if (
        cert.extensions?.some(
          ext =>
            ext.critical &&
            (!SUPPORTED_CRITICAL_EXTENSIONS.has(ext.extnID) || !ext.parsedValue)
        )
      )
        reject();
      // PKIjs checks CA/key usage but does not enforce pathLenConstraint.
      const constraints = cert.extensions?.find(
        ext => ext.extnID === "2.5.29.19"
      )?.parsedValue;
      if (index > 0 && constraints?.pathLenConstraint !== undefined) {
        const intermediates = validation.certificatePath
          .slice(1, index)
          .filter(cert => !cert.subject.isEqual(cert.issuer)).length;
        if (intermediates > constraints.pathLenConstraint) reject();
      }
    }
    const expires = Math.min(
      clock() + CACHE_TTL_MS,
      ...validation.certificatePath.map(cert => cert.notAfter.value.getTime())
    );
    if (expires <= clock()) reject();
    if (cache.size >= MAX_CACHE_ENTRIES)
      cache.delete(cache.keys().next().value!);
    cache.set(url, { certificate: leaf, expires });
    return leaf;
  }

  return async (body, headers, skillId) => {
    const url = certificateUrl(headers.certificateUrl);
    const signature = headers.signature;
    if (
      !signature ||
      signature.length > 2048 ||
      !/^[A-Za-z0-9+/]+={0,2}$/.test(signature)
    )
      reject();
    const signatureBytes = Buffer.from(signature, "base64");
    if (signatureBytes.toString("base64") !== signature) reject();
    // Reject malformed/stale envelopes before doing network or certificate work.
    const envelope: unknown = JSON.parse(body.toString("utf8"));
    checkEnvelope(envelope, skillId, clock());
    const certificate = await signingCertificate(url);
    // OpenSSL verifies the complete PKCS#1 v1.5 DigestInfo; no forge ASN.1
    // signature parser is involved (including GHSA-86w9-cpqp-85rv's case).
    if (
      !verify(
        "RSA-SHA256",
        body,
        {
          key: certificate.publicKey,
          padding: constants.RSA_PKCS1_PADDING,
        },
        signatureBytes
      )
    )
      reject();
    return checkEnvelope(envelope, skillId, clock());
  };
}

export function createAlexaWebhookHandler(
  skill: Pick<Skill, "invoke">,
  skillId: string,
  verifyRequest = createAlexaRequestVerifier()
): RequestHandler {
  const raw = express.raw({ type: "*/*", limit: "1mb", inflate: false });
  return (req, res) => {
    res.setHeader("Cache-Control", "no-store");
    raw(req, res, error => {
      void (async () => {
        let envelope: RequestEnvelope;
        try {
          if (error || !Buffer.isBuffer(req.body)) reject();
          envelope = await verifyRequest(
            req.body,
            {
              certificateUrl: req.get("SignatureCertChainUrl"),
              signature: req.get("Signature-256"),
            },
            skillId
          );
        } catch {
          res.status(400).json({ error: "invalid_alexa_request" });
          return;
        }
        try {
          res.status(200).json(await skill.invoke(envelope));
        } catch {
          res.status(500).json({ error: "server_error" });
        }
      })();
    });
  };
}
