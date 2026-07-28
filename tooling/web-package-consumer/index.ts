import * as Magrathea from "../../build/web-package/magrathea-web-client";
import WebClient = Magrathea.saien.magrathea.web.client;

const client = WebClient.createMagratheaWebChatbot(
  "https://gateway.example",
  "typescript-consumer",
);
const model = new WebClient.MagratheaWebChatModel(
  "gateway-provider",
  "chat-model",
  "Chat model",
  true,
  false,
  true,
  128_000,
);

const sessionPromise: Promise<WebClient.MagratheaWebChatSession> = client.createSession(model);
const historyPromise: Promise<Array<WebClient.MagratheaWebChatHistoryItem>> = client.history();

void sessionPromise.then((session) => {
  const observation = session.observe((snapshot: WebClient.MagratheaWebChatSnapshot) => {
    const status: string = snapshot.status;
    const provider: string = snapshot.model.provider;
    const text: string = snapshot.messages.map((message) => message.text).join("");
    const toolCalls: Array<WebClient.MagratheaWebChatToolCall> = snapshot.messages.flatMap(
      (message) => message.toolCalls,
    );
    const citations: Array<WebClient.MagratheaWebChatCitation> = snapshot.messages.flatMap(
      (message) => message.toolResults.flatMap((result) => result.citations),
    );
    const attachments: Array<WebClient.MagratheaWebChatAttachment> = snapshot.messages.flatMap(
      (message) => message.attachments,
    );
    void status;
    void provider;
    void text;
    void toolCalls;
    void citations;
    void attachments;
  });
  observation.cancel();
  return session.cancel();
});

void historyPromise;
void client.close();
