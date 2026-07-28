# KMP Published Consumer

This isolated build resolves every publishable KMP module (Core, Provider API, reference Providers,
Runtime, Chatbot, Policy, storage, credentials, and Gateway components) from the repository supplied
through `-Pmagrathea.repository`. The root `verifyKmpPublishedConsumerJvmAndroid` and
`verifyKmpPublishedConsumerApple` tasks publish every SDK target publication to a build-local Maven
repository before compiling this consumer for Android, JVM, `iosArm64`, and `iosSimulatorArm64`.
The Apple gate also links the complete resolved mobile graph into one static consumer framework for
each supported Apple architecture. Individual Magrathea modules are consumed as KMP target
publications; final Apple framework construction lives at the consumer boundary.
