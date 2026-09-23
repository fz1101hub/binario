import { describe, expect, it } from "vitest";
import { isAllowedRedirectUri, parseOAuthAuthorizationParameters } from "./oauthContract";
import { isAlexaConfigured } from "./config";

const ALLOWED = ["https://alexa.amazon.com/callback", "https://layla.amazon.com/api/skill/link/M1"];

describe("contratto OAuth e fail-closed Alexa", () => {
  it("accetta soltanto authorization code grant HTTPS del client previsto verso una callback registrata", () => {
    expect(parseOAuthAuthorizationParameters({ response_type: "code", client_id: "alexa-client", redirect_uri: "https://alexa.amazon.com/callback", state: "opaque-state" }, "alexa-client", ALLOWED)).toMatchObject({
      responseType: "code", clientId: "alexa-client", state: "opaque-state", scope: "routes.read",
    });
  });

  it("rifiuta redirect non HTTPS, stato assente e client inatteso", () => {
    expect(parseOAuthAuthorizationParameters({ response_type: "code", client_id: "alexa-client", redirect_uri: "http://example.test", state: "s" }, "alexa-client", ["http://example.test"])).toBeNull();
    expect(parseOAuthAuthorizationParameters({ response_type: "code", client_id: "other-client", redirect_uri: "https://alexa.amazon.com/callback", state: "s" }, "alexa-client", ALLOWED)).toBeNull();
    expect(parseOAuthAuthorizationParameters({ response_type: "code", client_id: "alexa-client", redirect_uri: "https://alexa.amazon.com/callback" }, "alexa-client", ALLOWED)).toBeNull();
  });

  it("rifiuta redirect non presenti nell'allowlist, anche se HTTPS o simili a una callback valida", () => {
    expect(isAllowedRedirectUri("https://attacker.example/callback", ALLOWED)).toBe(false);
    expect(isAllowedRedirectUri("https://alexa.amazon.com/callback/../evil", ALLOWED)).toBe(false);
    expect(isAllowedRedirectUri("https://alexa.amazon.com/callback?x=1", ALLOWED)).toBe(false);
    expect(isAllowedRedirectUri("https://alexa.amazon.com.attacker.example/callback", ALLOWED)).toBe(false);
    expect(isAllowedRedirectUri("https://alexa.amazon.com/callback", [])).toBe(false);
    expect(parseOAuthAuthorizationParameters({ response_type: "code", client_id: "alexa-client", redirect_uri: "https://attacker.example", state: "s" }, "alexa-client", ALLOWED)).toBeNull();
  });

  it("resta non attivo in assenza delle credenziali Alexa", () => {
    expect(isAlexaConfigured()).toBe(false);
  });
});
