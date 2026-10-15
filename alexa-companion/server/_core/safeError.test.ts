import axios from "axios";
import { describe, expect, it } from "vitest";
import { safeErrorDetails } from "./safeError";

describe("safeErrorDetails", () => {
  it("non restituisce credenziali da body, query o header Axios", () => {
    const error = new axios.AxiosError(
      "upstream rejected request",
      "ERR_BAD_REQUEST",
      {
        headers: new axios.AxiosHeaders({
          Authorization: "Bearer secret-access",
          Cookie: "session=secret-cookie",
          "X-Correlation-Id": "safe-correlation",
        }),
        method: "post",
        url: "https://oauth.example/token?code=secret-code",
        data: "grant_type=authorization_code&code=secret-code&jwtToken=secret-jwt",
      }
    );

    const details = safeErrorDetails(error);
    const serialized = JSON.stringify(details);
    expect(serialized).not.toContain("secret-access");
    expect(serialized).not.toContain("secret-cookie");
    expect(serialized).not.toContain("secret-code");
    expect(serialized).not.toContain("secret-jwt");
    expect(serialized).toContain("safe-correlation");
    expect(details).not.toHaveProperty("config.data");
    expect(details).toHaveProperty("config.url", "https://oauth.example/token");
  });
});
