import { describe, expect, it } from "vitest";
import { parseOAuthAuthorizationParameters } from "./oauthContract";
import { isAlexaConfigured } from "./config";

describe("contratto OAuth e fail-closed Alexa", () => {
  it("accetta soltanto authorization code grant HTTPS del client previsto", () => {
    expect(parseOAuthAuthorizationParameters({ response_type: "code", client_id: "alexa-client", redirect_uri: "https://alexa.amazon.com/callback", state: "opaque-state" }, "alexa-client")).toMatchObject({
      responseType: "code", clientId: "alexa-client", state: "opaque-state", scope: "routes.read",
    });
  });

  it("rifiuta redirect non HTTPS, stato assente e client inatteso", () => {
    expect(parseOAuthAuthorizationParameters({ response_type: "code", client_id: "alexa-client", redirect_uri: "http://example.test", state: "s" }, "alexa-client")).toBeNull();
    expect(parseOAuthAuthorizationParameters({ response_type: "code", client_id: "other-client", redirect_uri: "https://example.test", state: "s" }, "alexa-client")).toBeNull();
    expect(parseOAuthAuthorizationParameters({ response_type: "code", client_id: "alexa-client", redirect_uri: "https://example.test" }, "alexa-client")).toBeNull();
  });

  it("resta non attivo in assenza delle credenziali Alexa", () => {
    expect(isAlexaConfigured()).toBe(false);
  });
});
