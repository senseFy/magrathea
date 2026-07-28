# Android chat sample

`AndroidChatSample.kt` is compiled by the isolated `tooling/android-consumer` build against
build-local published chatbot, Runtime, Gemini Provider, Room, and credentials coordinates.

It directly composes the public Provider-neutral chatbot facade, injects the API key through
Android Keystore, observes immutable snapshots, sends and cancels messages, resumes persisted
sessions, lists history, and closes Provider and Room resources.
