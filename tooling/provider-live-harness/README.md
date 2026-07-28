# Provider Live Harness

This tooling application performs explicitly authorized smoke tests against a remote Gemini,
OpenAI, or Anthropic API. It is not an SDK module and is not published. Its remote `run` task is
not invoked by the normal deterministic test suites; only its offline configuration and redaction
tests are part of the repository gates.

Each run can incur Provider charges. Select one Provider, supply only its environment credential,
and choose the model deliberately:

```bash
MAGRATHEA_GEMINI_API_KEY=... \
  ./gradlew :provider-live-harness:run --args="provider=gemini scenario=chat streaming=true model=gemini-2.5-flash"
```

Supported scenarios are `chat`, `file`, `mixed-tools`, `resume`, and `x-search`. `prompt=...`
overrides the short default prompt. The process fails on Provider failure, cancellation, missing
terminal completion, or an invalid scenario, and it closes Provider transports and temporary Room
resources before exiting. Paid runs default to `maxTokens=128` for `chat`, `maxTokens=256` for
client-managed tool scenarios, `maxTokens=8192` for the reasoning-heavy `x-search` scenario, and
`maxProviderRetries=0`. These can be set explicitly or through `MAGRATHEA_MAX_TOKENS` and
`MAGRATHEA_MAX_PROVIDER_RETRIES`. Terminal output reports only aggregate input, output, and
reasoning-token usage.

`file` sends one explicitly selected PDF, CSV, JSON, or plain-text file through the Provider's
canonical attachment request mapping. It requires an absolute `file=/path/to/document.pdf` argument
or `MAGRATHEA_FILE`, enforces a 10 MiB limit, and never prints the path or file content.

An exact protocol-compatible service can be selected without adding another Provider module. Pass
its full HTTPS endpoint and, when its authentication differs from the protocol default, an explicit
authentication mode:

```bash
MAGRATHEA_ANTHROPIC_API_KEY=... \
  ./gradlew :provider-live-harness:run \
  --args="provider=anthropic scenario=chat streaming=true model=provider/model endpoint=https://compatible.example/api/v1/messages authentication=bearer"
```

For an OpenAI-family endpoint, select the exact wire contract explicitly. The default is Responses;
Chat Completions uses `api=chat-completions` (or `MAGRATHEA_OPENAI_API=chat-completions`):

```bash
MAGRATHEA_OPENAI_API_KEY=... \
  ./gradlew :provider-live-harness:run \
  --args="provider=openai api=chat-completions scenario=chat streaming=true model=provider/model endpoint=https://compatible.example/v1/chat/completions"
```

The `x-search` scenario validates an OpenAI Responses-compatible hosted X Search implementation,
including provider-managed search traces and grounded citation metadata:

```bash
MAGRATHEA_OPENAI_API_KEY=... \
  ./gradlew :provider-live-harness:run \
  --args="provider=openai scenario=x-search model=grok-4.5 endpoint=https://api.x.ai/v1/responses authentication=bearer"
```

OpenAI accepts `bearer` or `api-key`; Anthropic accepts `x-api-key` or `bearer`. The corresponding
environment settings are `MAGRATHEA_ENDPOINT` and `MAGRATHEA_AUTHENTICATION`. An authentication
override requires an explicit HTTPS endpoint. The configured service must implement the exact
selected OpenAI Responses, OpenAI Chat Completions, or Anthropic Messages contract, including its
streaming and tool behavior.

Harness output contains only status, counts, lengths, and metadata keys. It does not print API
keys, prompts, model output, reasoning content, tool payloads, or Provider signatures.
