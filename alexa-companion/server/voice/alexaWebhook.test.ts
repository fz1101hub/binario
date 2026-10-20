import express from "express";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { Server } from "node:http";
import type { AddressInfo } from "node:net";
import {
  constants,
  createHash,
  KeyObject,
  privateEncrypt,
  sign,
  webcrypto,
} from "node:crypto";
import * as asn1 from "asn1js";
import {
  AttributeTypeAndValue,
  BasicConstraints,
  Certificate,
  CryptoEngine,
  Extension,
  GeneralSubtree,
  GeneralName,
  GeneralNames,
  NameConstraints,
} from "pkijs";
import {
  createAlexaRequestVerifier,
  createAlexaWebhookHandler,
} from "./alexaWebhook";

const NOW = Date.parse("2026-10-15T12:00:00Z");
const SKILL_ID = "amzn1.ask.skill.binario-test";
const CERT_URL = "https://s3.amazonaws.com/echo.api/test.pem";
const engine = new CryptoEngine({ crypto: webcrypto as unknown as Crypto });
type Keys = Awaited<ReturnType<typeof keys>>;
let rootKeys: Keys, intermediateKeys: Keys, leafKeys: Keys;
let root: Certificate, intermediate: Certificate, leaf: Certificate;
let server: Server | undefined;
let serial = 1;

async function keys() {
  return webcrypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"]
  );
}

async function issue(options: {
  name: string;
  keys: Keys;
  issuer?: Certificate;
  issuerKeys?: Keys;
  ca?: boolean;
  san?: string | null;
  from?: number;
  until?: number;
  unknownCriticalExtension?: boolean;
  pathLen?: number;
  permittedDomain?: string;
}) {
  const cert = new Certificate();
  cert.version = 2;
  cert.serialNumber = new asn1.Integer({ value: serial++ });
  cert.subject.typesAndValues.push(
    new AttributeTypeAndValue({
      type: "2.5.4.3",
      value: new asn1.Utf8String({ value: options.name }),
    })
  );
  cert.issuer = options.issuer?.subject ?? cert.subject;
  cert.notBefore.value = new Date(options.from ?? NOW - 60_000);
  cert.notAfter.value = new Date(options.until ?? NOW + 86_400_000);
  await cert.subjectPublicKeyInfo.importKey(options.keys.publicKey, engine);
  cert.extensions = [
    new Extension({
      extnID: "2.5.29.19",
      critical: true,
      extnValue: new BasicConstraints({
        cA: options.ca ?? false,
        ...(options.pathLen === undefined
          ? {}
          : { pathLenConstraint: options.pathLen }),
      })
        .toSchema()
        .toBER(false),
    }),
    new Extension({
      extnID: "2.5.29.15",
      critical: true,
      extnValue: new asn1.BitString({
        unusedBits: options.ca ? 1 : 7,
        valueHex: new Uint8Array([options.ca ? 0x06 : 0x80]).buffer,
      }).toBER(false),
    }),
  ];
  if (options.san !== null && !options.ca)
    cert.extensions.push(
      new Extension({
        extnID: "2.5.29.17",
        extnValue: new GeneralNames({
          names: [
            new GeneralName({
              type: 2,
              value: options.san ?? "echo-api.amazon.com",
            }),
          ],
        })
          .toSchema()
          .toBER(false),
      })
    );
  if (options.permittedDomain)
    cert.extensions.push(
      new Extension({
        extnID: "2.5.29.30",
        critical: true,
        extnValue: new NameConstraints({
          permittedSubtrees: [
            new GeneralSubtree({
              base: new GeneralName({
                type: 2,
                value: options.permittedDomain,
              }),
            }),
          ],
        })
          .toSchema()
          .toBER(false),
      })
    );
  if (options.unknownCriticalExtension)
    cert.extensions.push(
      new Extension({
        extnID: "1.2.3.4.5",
        critical: true,
        extnValue: new asn1.Null().toBER(false),
      })
    );
  await cert.sign(
    options.issuerKeys?.privateKey ?? options.keys.privateKey,
    "SHA-256",
    engine
  );
  return cert;
}

function pem(cert: Certificate) {
  return `-----BEGIN CERTIFICATE-----\n${Buffer.from(cert.toSchema().toBER(false)).toString("base64")}\n-----END CERTIFICATE-----`;
}

function body(overrides: Record<string, unknown> = {}) {
  return Buffer.from(
    JSON.stringify({
      version: "1.0",
      context: { System: { application: { applicationId: SKILL_ID } } },
      request: {
        type: "LaunchRequest",
        requestId: "test",
        timestamp: new Date(NOW).toISOString(),
      },
      ...overrides,
    })
  );
}

function headers(data: Buffer = body()) {
  return {
    certificateUrl: CERT_URL,
    signature: sign(
      "RSA-SHA256",
      data,
      KeyObject.from(leafKeys.privateKey)
    ).toString("base64"),
  };
}

function verifier(
  chain = `${pem(leaf)}\n${pem(intermediate)}`,
  now = () => NOW
) {
  const fetchCertificateChain = vi.fn(async () => chain);
  return {
    fetchCertificateChain,
    verify: createAlexaRequestVerifier({
      trustedCertificates: [pem(root)],
      fetchCertificateChain,
      now,
    }),
  };
}

// Deliberately add an extra nested field to AlgorithmIdentifier. Strict OpenSSL
// must reject this DigestInfo even though the OID and SHA-256 digest are correct.
function malformedSignature(data: Buffer, key: Keys["privateKey"]) {
  const digestInfo = Buffer.from(
    new asn1.Sequence({
      value: [
        new asn1.Sequence({
          value: [
            new asn1.ObjectIdentifier({ value: "2.16.840.1.101.3.4.2.1" }),
            new asn1.Null(),
            new asn1.Sequence({ value: [new asn1.Null()] }),
          ],
        }),
        new asn1.OctetString({
          valueHex: Uint8Array.from(createHash("sha256").update(data).digest())
            .buffer,
        }),
      ],
    }).toBER(false)
  );
  const encoded = Buffer.concat([
    Buffer.from([0, 1]),
    Buffer.alloc(256 - digestInfo.length - 3, 0xff),
    Buffer.from([0]),
    digestInfo,
  ]);
  return privateEncrypt(
    { key: KeyObject.from(key), padding: constants.RSA_NO_PADDING },
    encoded
  );
}

beforeAll(async () => {
  [rootKeys, intermediateKeys, leafKeys] = await Promise.all([
    keys(),
    keys(),
    keys(),
  ]);
  root = await issue({ name: "Test Root", keys: rootKeys, ca: true });
  intermediate = await issue({
    name: "Test Intermediate",
    keys: intermediateKeys,
    issuer: root,
    issuerKeys: rootKeys,
    ca: true,
  });
  leaf = await issue({
    name: "echo-api.amazon.com",
    keys: leafKeys,
    issuer: intermediate,
    issuerKeys: intermediateKeys,
  });
}, 30_000);

afterEach(async () => {
  if (server)
    await new Promise<void>(resolve => server!.close(() => resolve()));
  server = undefined;
});

describe("Alexa signed web-service requests", () => {
  it("accepts a valid signed body with a trusted certificate chain", async () => {
    expect(await verifier().verify(body(), headers(), SKILL_ID)).toMatchObject({
      request: { type: "LaunchRequest" },
    });
  });

  it("does not trust a request-supplied root or test root by default", async () => {
    const verify = createAlexaRequestVerifier({
      fetchCertificateChain: async () =>
        `${pem(leaf)}\n${pem(intermediate)}\n${pem(root)}`,
      now: () => NOW,
    });
    await expect(verify(body(), headers(), SKILL_ID)).rejects.toThrow();
  });

  it.each([
    "http://s3.amazonaws.com/echo.api/test.pem",
    "https://s3.amazonaws.com.attacker.test/echo.api/test.pem",
    "https://s3.amazonaws.com:444/echo.api/test.pem",
    "https://s3.amazonaws.com/other/test.pem",
    "https://s3.amazonaws.com/echo.api/../other/test.pem",
    "https://s3.amazonaws.com/echo.api/%2e%2e/other/test.pem",
    "https://user@s3.amazonaws.com/echo.api/test.pem",
    "https://s3.amazonaws.com/echo.api/test.pem?redirect=evil",
    "https://s3.amazonaws.com/echo.api/test.pem#fragment",
    "https://s3.amazonaws.com/echo.api/",
    "https://s3.amazonaws.com\\@attacker.test/echo.api/test.pem",
    undefined,
  ])(
    "rejects an unsafe certificate URL before network access: %s",
    async certificateUrl => {
      const { verify, fetchCertificateChain } = verifier();
      await expect(
        verify(body(), { ...headers(), certificateUrl }, SKILL_ID)
      ).rejects.toThrow();
      expect(fetchCertificateChain).not.toHaveBeenCalled();
    }
  );

  it.each([undefined, "!invalid!", "eA", "eA===", " ", "A".repeat(2049)])(
    "rejects missing or malformed Signature-256: %s",
    async signature => {
      const { verify, fetchCertificateChain } = verifier();
      await expect(
        verify(body(), { ...headers(), signature }, SKILL_ID)
      ).rejects.toThrow();
      expect(fetchCertificateChain).not.toHaveBeenCalled();
    }
  );

  it("rejects tampered raw bytes and forged PKCS#1 DigestInfo request signatures", async () => {
    const { verify } = verifier();
    await expect(
      verify(Buffer.concat([body(), Buffer.from(" ")]), headers(), SKILL_ID)
    ).rejects.toThrow();
    await expect(
      verify(
        body(),
        {
          ...headers(),
          signature: malformedSignature(body(), leafKeys.privateKey).toString(
            "base64"
          ),
        },
        SKILL_ID
      )
    ).rejects.toThrow();
  });

  it.each([
    { context: {} },
    { context: { System: { application: { applicationId: "wrong-skill" } } } },
    { session: { application: { applicationId: "wrong-skill" } } },
    { request: { type: "LaunchRequest", timestamp: "invalid" } },
    {
      request: {
        type: "LaunchRequest",
        timestamp: new Date(NOW - 150_001).toISOString(),
      },
    },
    {
      request: {
        type: "LaunchRequest",
        timestamp: new Date(NOW + 150_001).toISOString(),
      },
    },
    { version: "2.0" },
  ])("rejects invalid envelope or skill identity: %j", async overrides => {
    const data = body(overrides);
    const { verify, fetchCertificateChain } = verifier();
    await expect(verify(data, headers(data), SKILL_ID)).rejects.toThrow();
    expect(fetchCertificateChain).not.toHaveBeenCalled();
  });

  it.each([
    { san: "wrong.amazon.com" },
    { san: "*.amazon.com" },
    { san: null },
    { until: NOW - 1 },
    { from: NOW + 1000 },
    { unknownCriticalExtension: true },
  ])("rejects an invalid signing leaf: %j", async options => {
    const invalidLeaf = await issue({
      name: "echo-api.amazon.com",
      keys: leafKeys,
      issuer: intermediate,
      issuerKeys: intermediateKeys,
      ...options,
    });
    await expect(
      verifier(`${pem(invalidLeaf)}\n${pem(intermediate)}`).verify(
        body(),
        headers(),
        SKILL_ID
      )
    ).rejects.toThrow();
  });

  it("enforces DNS name constraints and CA path-length limits", async () => {
    const restrictedIssuer = await issue({
      name: "Test Intermediate",
      keys: intermediateKeys,
      issuer: root,
      issuerKeys: rootKeys,
      ca: true,
      permittedDomain: ".example.test",
    });
    await expect(
      verifier(`${pem(leaf)}\n${pem(restrictedIssuer)}`).verify(
        body(),
        headers(),
        SKILL_ID
      )
    ).rejects.toThrow();
    const restrictedRoot = await issue({
      name: "Test Root",
      keys: rootKeys,
      ca: true,
      pathLen: 0,
    });
    const verify = createAlexaRequestVerifier({
      trustedCertificates: [pem(restrictedRoot)],
      fetchCertificateChain: async () => `${pem(leaf)}\n${pem(intermediate)}`,
      now: () => NOW,
    });
    await expect(verify(body(), headers(), SKILL_ID)).rejects.toThrow();
    const permittedIssuer = await issue({
      name: "Test Intermediate",
      keys: intermediateKeys,
      issuer: root,
      issuerKeys: rootKeys,
      ca: true,
      pathLen: 0,
      permittedDomain: ".amazon.com",
    });
    await expect(
      verifier(`${pem(leaf)}\n${pem(permittedIssuer)}`).verify(
        body(),
        headers(),
        SKILL_ID
      )
    ).resolves.toHaveProperty("version", "1.0");
  });

  it("rejects forged certificate signatures with malformed DigestInfo (GHSA-86w9-cpqp-85rv)", async () => {
    const forged = Certificate.fromBER(leaf.toSchema().toBER(false));
    forged.signatureValue = new asn1.BitString({
      valueHex: Uint8Array.from(
        malformedSignature(
          Buffer.from(forged.tbsView),
          intermediateKeys.privateKey
        )
      ).buffer,
    });
    await expect(
      verifier(`${pem(forged)}\n${pem(intermediate)}`).verify(
        body(),
        headers(),
        SKILL_ID
      )
    ).rejects.toThrow();
  });

  it("rejects missing intermediates, expired intermediates and non-CA issuers", async () => {
    await expect(
      verifier(pem(leaf)).verify(body(), headers(), SKILL_ID)
    ).rejects.toThrow();
    for (const options of [
      { until: NOW - 1 },
      { from: NOW + 1000 },
      { ca: false },
    ]) {
      const invalidIssuer = await issue({
        name: "Test Intermediate",
        keys: intermediateKeys,
        issuer: root,
        issuerKeys: rootKeys,
        ca: true,
        ...options,
      });
      await expect(
        verifier(`${pem(leaf)}\n${pem(invalidIssuer)}`).verify(
          body(),
          headers(),
          SKILL_ID
        )
      ).rejects.toThrow();
    }
  });

  it.each([
    "",
    "garbage",
    "-----BEGIN CERTIFICATE-----\ninvalid\n-----END CERTIFICATE-----",
    "x".repeat(65537),
  ])("rejects malformed or oversized certificate chains", async chain => {
    await expect(
      verifier(chain).verify(body(), headers(), SKILL_ID)
    ).rejects.toThrow();
  });

  it("bounds the cache and fetches a fresh chain after its TTL", async () => {
    let now = NOW;
    const { verify, fetchCertificateChain } = verifier(undefined, () => now);
    await verify(body(), headers(), SKILL_ID);
    await verify(body(), headers(), SKILL_ID);
    expect(fetchCertificateChain).toHaveBeenCalledTimes(1);
    for (let i = 0; i < 64; i++)
      await verify(
        body(),
        { ...headers(), certificateUrl: `${CERT_URL}${i}` },
        SKILL_ID
      );
    await verify(body(), headers(), SKILL_ID);
    expect(fetchCertificateChain).toHaveBeenCalledTimes(66);
    now += 300_001;
    const fresh = body({
      request: {
        type: "LaunchRequest",
        timestamp: new Date(now).toISOString(),
      },
    });
    await verify(fresh, headers(fresh), SKILL_ID);
    expect(fetchCertificateChain).toHaveBeenCalledTimes(67);
  });

  it("does not reuse cached certificates after their validity expires", async () => {
    let now = NOW;
    const shortLived = await issue({
      name: "echo-api.amazon.com",
      keys: leafKeys,
      issuer: intermediate,
      issuerKeys: intermediateKeys,
      until: NOW + 1000,
    });
    const { verify, fetchCertificateChain } = verifier(
      `${pem(shortLived)}\n${pem(intermediate)}`,
      () => now
    );
    await verify(body(), headers(), SKILL_ID);
    now += 2000;
    await expect(verify(body(), headers(), SKILL_ID)).rejects.toThrow();
    expect(fetchCertificateChain).toHaveBeenCalledTimes(2);
  });

  it("fails closed when fetching the chain fails", async () => {
    const verify = createAlexaRequestVerifier({
      fetchCertificateChain: async () => {
        throw new Error("unavailable");
      },
      now: () => NOW,
    });
    await expect(verify(body(), headers(), SKILL_ID)).rejects.toThrow();
  });
});

describe("Alexa HTTP dispatch", () => {
  async function start(preparse = false) {
    const invoke = vi.fn(async () => ({ version: "1.0", response: {} }));
    const app = express();
    if (preparse) app.use(express.json());
    app.post(
      "/webhook",
      createAlexaWebhookHandler({ invoke }, SKILL_ID, verifier().verify)
    );
    server = app.listen(0);
    await new Promise<void>(resolve =>
      server!.once("listening", () => resolve())
    );
    const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/webhook`;
    const signed = headers();
    return {
      invoke,
      post: (data: string | Buffer = body()) =>
        fetch(url, {
          method: "POST",
          body: data,
          headers: {
            "content-type": "application/json",
            "Signature-256": signed.signature,
            SignatureCertChainUrl: signed.certificateUrl,
          },
        }),
    };
  }

  it("dispatches only authenticated requests and returns generic verification errors", async () => {
    const { invoke, post } = await start();
    const response = await post();
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ version: "1.0", response: {} });
    expect(invoke).toHaveBeenCalledTimes(1);
    for (const data of [
      "invalid json",
      `${body()} `,
      "x".repeat(1024 * 1024 + 1),
    ]) {
      const rejected = await post(data);
      expect(rejected.status).toBe(400);
      expect(await rejected.json()).toEqual({ error: "invalid_alexa_request" });
      expect(rejected.headers.get("cache-control")).toBe("no-store");
    }
    expect(invoke).toHaveBeenCalledTimes(1);
  });

  it("rejects pre-parsed bodies before dispatch", async () => {
    const { invoke, post } = await start(true);
    expect((await post()).status).toBe(400);
    expect(invoke).not.toHaveBeenCalled();
  });

  it("keeps unexpected skill errors out of HTTP responses", async () => {
    const { invoke, post } = await start();
    invoke.mockRejectedValueOnce(new Error("private database detail"));
    const response = await post();
    expect(response.status).toBe(500);
    expect(await response.json()).toEqual({ error: "server_error" });
  });
});
