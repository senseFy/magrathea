# Image Search

Magrathea exposes image search as a Provider-neutral Tool backed by an application-supplied
`ImageSearchBackend`.

## Data flow

1. The model calls `image_search` with a query.
2. The backend returns normalized image URLs, source pages, optional previews, dimensions, MIME
   types, descriptions, publishers, and license data.
3. `ImageSearchTool` validates and bounds the result.
4. Structured metadata, including a stable `mediaReference`, returns to the model for synthesis.
5. Typed image blocks and their references remain in canonical history and are projected by
   `magrathea-chatbot` for product rendering.

`MediaReference` is runtime-owned and derived from the replay-stable Tool execution identity. A
model can return a selected reference through product-defined structured or textual output.
Products resolve only references present in canonical Tool results; arbitrary remote image URLs
remain untrusted text or links.

Search images use the `USER` audience by default. They are not sent to a model as image input.
Tools that intentionally return images for model inspection must use the `MODEL` audience, and the
selected `ModelDescriptor` must declare `ModelInputModality.IMAGE`.

## Policy

`ImageSearchPolicy` controls:

| Setting | Default |
|---|---:|
| Calls per logical run | `3` |
| Images per query | `8` |
| Query length | `512` characters |
| Description length | `600` characters |
| Freshness | `AUTO` |
| Safe Search | `STRICT` |
| Timeout | `12 s` |

Allowed or blocked domains, locale, and consented approximate location use shared portable search
value types. A backend must honor the request or report
`ImageSearchFailureCode.UNSUPPORTED_POLICY`.

`ImageSearchTool` defaults to `ToolRecoveryPolicy.REPLAY_SAFE`. A host whose backend must not repeat
an invocation with an unknown outcome sets `recoveryPolicy = ToolRecoveryPolicy.FAIL_CLOSED` when
constructing the Tool. Recovery policy describes backend execution semantics and therefore remains
separate from `ImageSearchPolicy`.

## Composition

```kotlin
val imageSearch = ImageSearchTool(
    backend = applicationImageSearchBackend,
    policy = ImageSearchPolicy(
        maxResultsPerQuery = 8,
        safeSearch = SearchSafeSearch.STRICT,
    ),
)

val runner = DefaultAgentRunner(
    providerRegistry = providers,
    toolRegistry = InMemoryToolRegistry(listOf(imageSearch)),
    persistence = persistence,
    credentialProvider = providerCredentials,
)

val chatbot = createChatbotClient(
    runner = runner,
    requestFactory = DefaultChatbotRequestFactory(
        tools = listOf(imageSearch.definition),
    ),
    persistence = persistence,
)
```

The backend owns its transport, credentials, service-specific options, and lifecycle. Products
render `ChatbotToolResult.images`, resolve `ChatbotToolImage.reference`, and should link each image
to its attribution source.

The SDK bounds URL length, accepts only syntactically valid HTTPS URLs, applies source-page domain
policy, deduplicates image URLs, validates declared image MIME types, and bounds result metadata.
It does not fetch remote media. The product's media loader owns DNS and redirect egress policy,
private-network blocking, response size and content-type checks, caching, and rendering isolation.
