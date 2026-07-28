# JVM chatbot and protocol-mock sample

This standalone application consumes the published `magrathea-runtime` and `magrathea-chatbot`
coordinates from the build-local Maven repository. It uses no network and no credential. Its
scripted provider emits the same canonical `ProviderEvent` lifecycle that a wire adapter must
produce, including streaming text and a finalized tool call.

The executable and its test verify:

- two-chunk canonical text streaming;
- one finalized tool call and exactly one side effect;
- completed-session resume without another provider call;
- cancellation persisted as `CANCELLED`;
- session history containing both runs;
- the public Provider-neutral `createChatbotClient` path, terminal snapshot, history, and exactly-once
  resource close.

From the repository root, run `./gradlew verifyJvmChatSample`.
