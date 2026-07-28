.DEFAULT_GOAL := help

SHELL := /bin/bash

GRADLE ?= ./gradlew
GRADLE_ARGS ?=
PUBLISH_ARGS ?=
HOST_OS := $(shell uname -s)

.PHONY: \
	help tasks build test verify verify-linux verify-apple verify-web \
	verify-release verify-android-device api-check api-dump \
	verify-mcp-conformance publish-coordinates publish-local clean require-macos

help: ## Show the available commands.
	@printf 'Magrathea development commands:\n\n'
	@awk 'BEGIN { FS = ":.*## " } /^[a-zA-Z0-9_.-]+:.*## / { printf "  %-24s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@printf '\nVariables:\n'
	@printf '  GRADLE_ARGS             Extra arguments appended to Gradle commands\n'
	@printf '  PUBLISH_ARGS            Extra arguments passed to scripts/publish-sdk\n'

tasks: ## List the canonical Gradle verification tasks.
	$(GRADLE) $(GRADLE_ARGS) tasks --group verification

build: ## Assemble all SDK modules without running the verification gates.
	$(GRADLE) $(GRADLE_ARGS) assemble

test: verify ## Run the fast deterministic verification gate.

verify: ## Run deterministic tests, ABI checks, and contract checks.
	$(GRADLE) $(GRADLE_ARGS) verifySdkQuick

verify-linux: ## Verify the JVM, Android, and Linux-compatible SDK graph.
	$(GRADLE) $(GRADLE_ARGS) verifySdkLinux

verify-apple: require-macos ## Verify iOS tests, consumers, and Apple frameworks.
	$(GRADLE) $(GRADLE_ARGS) verifySdkApple

verify-web: ## Verify JS/Wasm, browser, package, and TypeScript contracts.
	$(GRADLE) $(GRADLE_ARGS) verifySdkWeb

verify-release: ## Run the complete local release-candidate gate.
	$(GRADLE) $(GRADLE_ARGS) clean verifySdkRelease

verify-android-device: ## Run the explicit deterministic physical-device gate.
	$(GRADLE) $(GRADLE_ARGS) verifyAndroidDevice

verify-mcp-conformance: ## Run the official MCP client conformance scenarios supported by the SDK.
	npx --yes @modelcontextprotocol/conformance@0.1.16 client --command "./scripts/mcp-conformance-client" --scenario initialize --spec-version 2025-11-25
	npx --yes @modelcontextprotocol/conformance@0.1.16 client --command "./scripts/mcp-conformance-client" --scenario tools_call --spec-version 2025-11-25

api-check: ## Check committed JVM ABI baselines and serialization compatibility.
	$(GRADLE) $(GRADLE_ARGS) verifySdkCompatibility

api-dump: ## Update JVM ABI baselines after an intentional public API change.
	$(GRADLE) $(GRADLE_ARGS) apiDump

publish-coordinates: ## Print the SDK coordinates without publishing artifacts.
	./scripts/publish-sdk --print $(PUBLISH_ARGS)

publish-local: ## Verify and publish the SDK to the local Maven repository.
	./scripts/publish-sdk $(PUBLISH_ARGS)

clean: ## Remove Gradle build outputs.
	$(GRADLE) $(GRADLE_ARGS) clean

require-macos:
	@test "$(HOST_OS)" = "Darwin" || { echo "This command requires macOS." >&2; exit 1; }
