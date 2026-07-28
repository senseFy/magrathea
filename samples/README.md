# Magrathea samples

All samples are compiled by release gates; they are not documentation-only snippets.

For the shortest external-consumer path, start with the Provider-neutral construction in the
[architecture guide](../docs/architecture.md#provider-neutral-runtime-and-chatbot-facade). The
samples below intentionally go further and exercise publication, protocol, persistence, lifecycle,
and browser boundaries.

| Sample | Consumption boundary | Automated gate | Covered behavior |
|---|---|---|---|
| [Android chat](android-chat) | Build-local chatbot, Provider, Room, and credentials artifacts | `verifyPublishedAndroidConsumer` | direct composition, credential injection, observe/send/cancel, resume/history, close |
| [JVM chat + protocol mock](jvm-chat) | Build-local `magrathea-runtime` + Provider-neutral `magrathea-chatbot` | `verifyJvmChatSample` | canonical streaming, exactly-once tool, public facade composition, cancel, resume, history, close |
| [Web chat](web-chat) | Build-local `magrathea-web-client` JS/Wasm variants | `verifyWebChatSample` / `verifyWebCrossBrowserRuntime` | authenticated real HTTP/SSE, IndexedDB, stream completion, cancel, JS/Wasm production bundles |

Run the sample gates from the repository root:

```bash
./gradlew verifyPublishedAndroidConsumer verifyJvmChatSample verifyWebChatSample
```

Samples never embed provider credentials. Android/iOS hosts inject them through platform credential
APIs; the JVM sample is deliberately offline and deterministic.
