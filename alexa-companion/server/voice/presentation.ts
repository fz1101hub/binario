import { getRequestType, type ResponseInterceptor } from "ask-sdk-core";
import { escapeSsmlText, plainSpeechText } from "./speech";

/** Echo speakers keep the same voice/card response. Screen devices get a small, offline APL view. */
export const screenResponseInterceptor: ResponseInterceptor = {
  process(input, response) {
    const interfaces = input.requestEnvelope.context.System.device?.supportedInterfaces;
    if (!response || !interfaces?.["Alexa.Presentation.APL"] || response.card?.type !== "Simple") return;
    const version = /^(\d+)\.(\d+)(?:\.\d+)?$/.exec(interfaces["Alexa.Presentation.APL"].runtime?.maxVersion ?? "");
    if (!version || Number(version[1]) < 1 || (Number(version[1]) === 1 && Number(version[2]) < 4)) return;
    const request = input.requestEnvelope.request;
    if (getRequestType(input.requestEnvelope) === "SessionEndedRequest") return;
    if (request.type === "IntentRequest" && ["AMAZON.StopIntent", "AMAZON.CancelIntent", "AMAZON.NavigateHomeIntent"].includes(request.intent.name)) return;

    const title = plainSpeechText(response.card.title ?? "Binario");
    const content = plainSpeechText(response.card.content ?? "");
    if (!content) return;
    response.directives = [...(response.directives ?? []), {
      type: "Alexa.Presentation.APL.RenderDocument",
      token: "binario-summary-v1",
      document: {
        type: "APL",
        version: "1.4",
        theme: "dark",
        mainTemplate: {
          parameters: ["payload"],
          items: [{
            type: "Frame", width: "100vw", height: "100vh", backgroundColor: "#101D19",
            item: {
              type: "ScrollView", width: "100%", height: "100%",
              item: {
                type: "Container", width: "100%", paddingLeft: "10vw", paddingRight: "10vw", paddingTop: "8vh", paddingBottom: "8vh",
                items: [
                  { type: "Text", text: "${payload.summary.title}", accessibilityLabel: title, color: "#7FE2BA", fontSize: "32dp", fontWeight: "700" },
                  { type: "Text", text: "${payload.summary.content}", accessibilityLabel: content, color: "#FFFFFF", fontSize: "24dp", spacing: "24dp" },
                ],
              },
            },
          }],
        },
      },
      // Binding data instead of concatenating document expressions also keeps route names inert.
      datasources: { summary: { title: escapeSsmlText(title), content: escapeSsmlText(content) } },
    }];
  },
};
