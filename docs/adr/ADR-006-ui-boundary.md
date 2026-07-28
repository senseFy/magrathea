# ADR-006: UI Boundary

- Status: Accepted
- Date: 2026-07-11

## Decision

Core, Runtime, and Chatbot APIs are UI-neutral. They expose immutable state, events, lifecycle
operations, and platform-neutral models without depending on Android UI, Compose Multiplatform,
SwiftUI, JavaFX, or another presentation toolkit.

Android applications may use Compose or Views, Apple applications may use SwiftUI or UIKit, JVM
desktop applications choose their own UI stack, and browser applications render the TypeScript/KMP
facade in their preferred framework.

## Consequences

Magrathea is an embeddable runtime SDK rather than an installable chatbot application. UI samples
demonstrate composition boundaries but do not define a design system or product architecture.
