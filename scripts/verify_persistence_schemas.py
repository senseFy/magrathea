#!/usr/bin/env python3
"""Validate Magrathea's owned, append-only logical persistence schema history."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any, Optional


class VerificationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise VerificationError(message)


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def load_json(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(), object_pairs_hook=reject_duplicate_keys)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"cannot read {label} at {path}: {error}")
    return expect_object(value, label)


def expect_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(f"{label} must be a JSON object")
    return value


def expect_array(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        fail(f"{label} must be a JSON array")
    return value


def expect_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        fail(f"{label} must be a non-empty string")
    return value


def expect_positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        fail(f"{label} must be a positive integer")
    return value


def expect_exact_keys(value: dict[str, Any], keys: set[str], label: str) -> None:
    actual = set(value)
    if actual != keys:
        fail(f"{label} keys must be {sorted(keys)}, got {sorted(actual)}")


def resolve_repo_path(root: Path, raw: Any, label: str) -> Path:
    value = expect_string(raw, label)
    relative = Path(value)
    if relative.is_absolute() or ".." in relative.parts:
        fail(f"{label} must stay inside the repository")
    resolved = (root / relative).resolve()
    try:
        resolved.relative_to(root)
    except ValueError:
        fail(f"{label} resolves outside the repository")
    return resolved


def heading_anchor(heading: str) -> str:
    normalized = heading.strip().lower()
    normalized = re.sub(r"[^\w\- ]", "", normalized, flags=re.UNICODE)
    return re.sub(r"[ -]+", "-", normalized).strip("-")


def verify_document_anchor(root: Path, raw: Any, label: str) -> None:
    reference = expect_string(raw, label)
    path_text, separator, anchor = reference.partition("#")
    if not separator or not anchor:
        fail(f"{label} must include a document path and heading anchor")
    path = resolve_repo_path(root, path_text, label)
    if not path.is_file():
        fail(f"{label} document does not exist: {path_text}")
    anchors = {
        heading_anchor(line.lstrip("#").strip())
        for line in path.read_text().splitlines()
        if line.startswith("#")
    }
    if anchor not in anchors:
        fail(f"{label} heading anchor does not exist: {anchor}")


def verify_regex_probe(
    root: Path,
    raw_probe: Any,
    expected_values: set[str],
    label: str,
) -> None:
    probe = expect_object(raw_probe, label)
    expect_exact_keys(probe, {"path", "pattern"}, label)
    path = resolve_repo_path(root, probe["path"], f"{label}.path")
    if not path.is_file():
        fail(f"{label}.path does not exist")
    pattern_text = expect_string(probe["pattern"], f"{label}.pattern")
    try:
        pattern = re.compile(pattern_text, re.MULTILINE | re.DOTALL)
    except re.error as error:
        fail(f"{label}.pattern is invalid: {error}")
    if pattern.groups != 1:
        fail(f"{label}.pattern must contain exactly one capture group")
    matches = pattern.findall(path.read_text())
    actual = set(matches)
    if actual != expected_values or len(matches) != len(expected_values):
        fail(
            f"{label} resolved {sorted(matches)}, "
            f"expected each of {sorted(expected_values)} exactly once"
        )


def verify_migration_source_isolation(source: str, label: str) -> None:
    forbidden = {
        r"\bStorageSchemaV[0-9]+Adapter\b": "a version adapter",
        r"\b(?:AgentSessionSnapshot|AgentCheckpoint)\s*\.\s*serializer\s*\(": (
            "a live persisted-root serializer"
        ),
    }
    for pattern, dependency in forbidden.items():
        if re.search(pattern, source):
            fail(f"{label} must not depend on {dependency}")


def verify_migration_implementation(
    root: Path,
    raw_implementation: Any,
    used_paths: set[str],
    label: str,
    verify_source: bool,
) -> str:
    implementation = expect_object(raw_implementation, label)
    expect_exact_keys(implementation, {"path", "sha256", "symbol"}, label)
    raw_path = expect_string(implementation["path"], f"{label}.path")
    if not raw_path.startswith("magrathea-core/src/commonMain/kotlin/") or not raw_path.endswith(".kt"):
        fail(f"{label}.path must be a commonMain Kotlin source owned by magrathea-core")
    if raw_path in used_paths:
        fail(f"migration implementation path is registered more than once: {raw_path}")
    used_paths.add(raw_path)

    expected_digest = expect_string(implementation["sha256"], f"{label}.sha256")
    if not re.fullmatch(r"[0-9a-f]{64}", expected_digest):
        fail(f"{label}.sha256 must be lowercase SHA-256")
    symbol = expect_string(implementation["symbol"], f"{label}.symbol")
    if not re.fullmatch(r"[A-Z][A-Za-z0-9]*", symbol):
        fail(f"{label}.symbol must be a Kotlin object type name")

    if verify_source:
        path = resolve_repo_path(root, raw_path, f"{label}.path")
        if not path.is_file():
            fail(f"{label}.path does not exist")
        source = path.read_text()
        declarations = re.findall(
            rf"\bobject\s+{re.escape(symbol)}\s*:\s*AdjacentStorageSchemaMigration\b",
            source,
        )
        if len(declarations) != 1:
            fail(
                f"{label}.symbol must declare exactly one object implementing "
                "AdjacentStorageSchemaMigration"
            )
        verify_migration_source_isolation(source, label)
        actual_digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual_digest != expected_digest:
            fail(f"{label} source changed: expected {expected_digest}, got {actual_digest}")
    return symbol


def verify_migration_registry_bindings(
    root: Path,
    raw_probe: Any,
    expected_bindings: set[tuple[str, int, str]],
    label: str,
) -> None:
    probe = expect_object(raw_probe, label)
    path = resolve_repo_path(root, probe["path"], f"{label}.path")
    source = path.read_text()
    for migration_id, from_version, symbol in expected_bindings:
        if migration_registry_binding_count(source, migration_id, from_version, symbol) != 1:
            fail(
                f"{label} must bind migration {migration_id!r} from schema {from_version} "
                f"to {symbol} exactly once"
            )


def migration_registry_binding_count(
    source: str,
    migration_id: str,
    from_version: int,
    symbol: str,
) -> int:
    registration = re.compile(
        rf"RegisteredStorageSchemaMigration\(\s*"
        rf"id\s*=\s*\"{re.escape(migration_id)}\"\s*,\s*"
        rf"fromVersion\s*=\s*{from_version}\s*,"
        rf"(?:(?!RegisteredStorageSchemaMigration\().)*?"
        rf"migration\s*=\s*{re.escape(symbol)}\s*,?\s*\)",
        re.MULTILINE | re.DOTALL,
    )
    return len(registration.findall(source))


def verify_codec_probe(root: Path, raw_probe: Any, label: str) -> list[str]:
    probe = expect_object(raw_probe, label)
    expect_exact_keys(probe, {"path", "requiredPatterns"}, label)
    path = resolve_repo_path(root, probe["path"], f"{label}.path")
    if not path.is_file():
        fail(f"{label}.path does not exist")
    patterns = [
        expect_string(value, f"{label}.requiredPatterns[{index}]")
        for index, value in enumerate(expect_array(probe["requiredPatterns"], f"{label}.requiredPatterns"))
    ]
    if len(patterns) != len(set(patterns)) or not patterns:
        fail(f"{label}.requiredPatterns must be unique and non-empty")
    source = path.read_text()
    missing = [pattern for pattern in patterns if pattern not in source]
    if missing:
        fail(f"{label} is missing production codec bindings: {missing}")
    return patterns


def verify_version_adapter(
    root: Path,
    raw_adapter: Any,
    version: int,
    minimum_readable: int,
    used_adapter_paths: set[str],
    used_fixture_paths: set[str],
    label: str,
) -> Optional[str]:
    if raw_adapter is None:
        if version >= minimum_readable:
            fail(f"{label} is required for every readable schema")
        return None

    adapter = expect_object(raw_adapter, label)
    expect_exact_keys(
        adapter,
        {"path", "sha256", "symbol", "versionPattern", "descriptorFixture"},
        label,
    )
    raw_path = expect_string(adapter["path"], f"{label}.path")
    if raw_path in used_adapter_paths:
        fail(f"schema adapter path is registered more than once: {raw_path}")
    used_adapter_paths.add(raw_path)
    path = resolve_repo_path(root, raw_path, f"{label}.path")
    if not path.is_file():
        fail(f"{label}.path does not exist")

    expected_digest = expect_string(adapter["sha256"], f"{label}.sha256")
    if not re.fullmatch(r"[0-9a-f]{64}", expected_digest):
        fail(f"{label}.sha256 must be lowercase SHA-256")
    actual_digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual_digest != expected_digest:
        fail(f"{label} source changed: expected {expected_digest}, got {actual_digest}")

    symbol = expect_string(adapter["symbol"], f"{label}.symbol")
    if not re.fullmatch(r"[A-Z][A-Za-z0-9]*", symbol):
        fail(f"{label}.symbol must be a Kotlin type name")
    if not re.search(rf"\bobject\s+{re.escape(symbol)}\b", path.read_text()):
        fail(f"{label}.symbol is not declared by {raw_path}")
    verify_regex_probe(
        root,
        {"path": raw_path, "pattern": adapter["versionPattern"]},
        {str(version)},
        f"{label}.versionPattern",
    )

    descriptor_label = f"{label}.descriptorFixture"
    descriptor_fixture = expect_object(adapter["descriptorFixture"], descriptor_label)
    expect_exact_keys(descriptor_fixture, {"path", "sha256"}, descriptor_label)
    descriptor_raw_path = expect_string(descriptor_fixture["path"], f"{descriptor_label}.path")
    if descriptor_raw_path in used_fixture_paths:
        fail(f"schema fixture path is registered more than once: {descriptor_raw_path}")
    used_fixture_paths.add(descriptor_raw_path)
    if f"/v{version}/" not in f"/{descriptor_raw_path}":
        fail(f"{descriptor_label}.path must identify schema v{version}")
    descriptor_path = resolve_repo_path(root, descriptor_raw_path, f"{descriptor_label}.path")
    if not descriptor_path.is_file():
        fail(f"{descriptor_label}.path does not exist")
    descriptor_digest = expect_string(
        descriptor_fixture["sha256"],
        f"{descriptor_label}.sha256",
    )
    if not re.fullmatch(r"[0-9a-f]{64}", descriptor_digest):
        fail(f"{descriptor_label}.sha256 must be lowercase SHA-256")
    actual_descriptor_digest = hashlib.sha256(descriptor_path.read_bytes()).hexdigest()
    if actual_descriptor_digest != descriptor_digest:
        fail(
            f"{descriptor_label} checksum changed: expected {descriptor_digest}, "
            f"got {actual_descriptor_digest}"
        )
    if not re.fullmatch(r"[0-9a-f]{64}", descriptor_path.read_text().strip()):
        fail(f"{descriptor_label} must contain one lowercase SHA-256 fingerprint")
    return symbol


def verify_fixture(
    root: Path,
    raw_fixture: Any,
    version: int,
    used_paths: set[str],
    label: str,
) -> str:
    fixture = expect_object(raw_fixture, label)
    expect_exact_keys(fixture, {"kind", "path", "sha256"}, label)
    kind = expect_string(fixture["kind"], f"{label}.kind")
    if kind not in {"session", "checkpoint"}:
        fail(f"{label}.kind must be session or checkpoint")
    raw_path = expect_string(fixture["path"], f"{label}.path")
    if raw_path in used_paths:
        fail(f"fixture path is registered more than once: {raw_path}")
    used_paths.add(raw_path)
    if f"/v{version}/" not in f"/{raw_path}":
        fail(f"{label}.path must identify schema v{version}")
    path = resolve_repo_path(root, raw_path, f"{label}.path")
    if not path.is_file():
        fail(f"{label}.path does not exist")
    expected_digest = expect_string(fixture["sha256"], f"{label}.sha256")
    if not re.fullmatch(r"[0-9a-f]{64}", expected_digest):
        fail(f"{label}.sha256 must be lowercase SHA-256")
    raw_bytes = path.read_bytes()
    actual_digest = hashlib.sha256(raw_bytes).hexdigest()
    if actual_digest != expected_digest:
        fail(f"{label} checksum changed: expected {expected_digest}, got {actual_digest}")
    payload = load_json(path, label)
    stored_version = expect_positive_int(payload.get("schemaVersion"), f"{label}.schemaVersion")
    if stored_version != version:
        fail(f"{label}.schemaVersion is {stored_version}, expected {version}")
    return kind


def validate_descriptor_refreezes(
    root: Path,
    raw_refreezes: Any,
    domains: list[Any],
    label: str,
    verify_documents: bool,
) -> list[dict[str, Any]]:
    refreezes = expect_array(raw_refreezes, label)
    domain_lookup = {
        expect_string(expect_object(domain, f"{label}.domain")["id"], f"{label}.domain.id"): domain
        for domain in domains
    }
    ids: list[str] = []
    last_digest_by_target: dict[tuple[str, int, str], str] = {}
    targets: dict[tuple[str, int, str], dict[str, Any]] = {}

    for index, raw_refreeze in enumerate(refreezes):
        refreeze_label = f"{label}[{index}]"
        refreeze = expect_object(raw_refreeze, refreeze_label)
        expect_exact_keys(
            refreeze,
            {
                "id",
                "domain",
                "version",
                "descriptorPath",
                "fromSha256",
                "toSha256",
                "decision",
                "reason",
            },
            refreeze_label,
        )
        refreeze_id = expect_string(refreeze["id"], f"{refreeze_label}.id")
        if not re.fullmatch(r"[a-z](?:[a-z0-9.-]{1,126}[a-z0-9])", refreeze_id):
            fail(f"{refreeze_label}.id must be a stable lowercase ID")
        if refreeze_id in ids:
            fail(f"{refreeze_label}.id is duplicated")
        ids.append(refreeze_id)

        domain_id = expect_string(refreeze["domain"], f"{refreeze_label}.domain")
        domain = domain_lookup.get(domain_id)
        if domain is None:
            fail(f"{refreeze_label}.domain is unknown")
        version = expect_positive_int(refreeze["version"], f"{refreeze_label}.version")
        version_entry = next(
            (entry for entry in domain["versions"] if entry.get("version") == version),
            None,
        )
        if version_entry is None:
            fail(f"{refreeze_label} targets an unknown schema version")
        adapter = expect_object(version_entry.get("adapter"), f"{refreeze_label}.adapter")
        descriptor = expect_object(
            adapter.get("descriptorFixture"), f"{refreeze_label}.descriptorFixture"
        )
        descriptor_path = expect_string(
            refreeze["descriptorPath"], f"{refreeze_label}.descriptorPath"
        )
        if descriptor.get("path") != descriptor_path:
            fail(f"{refreeze_label}.descriptorPath does not match the schema adapter")

        from_digest = expect_string(refreeze["fromSha256"], f"{refreeze_label}.fromSha256")
        to_digest = expect_string(refreeze["toSha256"], f"{refreeze_label}.toSha256")
        if not re.fullmatch(r"[0-9a-f]{64}", from_digest) or not re.fullmatch(
            r"[0-9a-f]{64}", to_digest
        ):
            fail(f"{refreeze_label} digests must be lowercase SHA-256")
        if from_digest == to_digest:
            fail(f"{refreeze_label} must change the descriptor digest")

        target = (domain_id, version, descriptor_path)
        previous_digest = last_digest_by_target.get(target)
        if previous_digest is not None and from_digest != previous_digest:
            fail(f"{refreeze_label}.fromSha256 does not continue its refreeze history")
        last_digest_by_target[target] = to_digest
        targets[target] = descriptor

        reason = expect_string(refreeze["reason"], f"{refreeze_label}.reason")
        if len(reason.strip()) < 40:
            fail(f"{refreeze_label}.reason must explain why persisted bytes are unchanged")
        if verify_documents:
            verify_document_anchor(root, refreeze["decision"], f"{refreeze_label}.decision")

    for target, final_digest in last_digest_by_target.items():
        if targets[target].get("sha256") != final_digest:
            fail(f"{label} final digest does not match the current schema ledger for {target}")
    return refreezes


def validate_ledger(
    root: Path,
    ledger: dict[str, Any],
    label: str,
    verify_sources_and_files: bool = True,
) -> dict[str, Any]:
    required_keys = {"ledgerFormatVersion", "policyDocument", "migrationIds", "domains"}
    optional_keys = {"descriptorRefreezes"}
    actual_keys = set(ledger)
    if not required_keys.issubset(actual_keys) or actual_keys - required_keys - optional_keys:
        fail(
            f"{label} keys must be {sorted(required_keys)} with optional "
            f"{sorted(optional_keys)}, got {sorted(actual_keys)}"
        )
    if ledger["ledgerFormatVersion"] != 1:
        fail(f"{label}.ledgerFormatVersion must be 1")
    if verify_sources_and_files:
        verify_document_anchor(root, ledger["policyDocument"], f"{label}.policyDocument")
    migration_ids = [
        expect_string(value, f"{label}.migrationIds[{index}]")
        for index, value in enumerate(expect_array(ledger["migrationIds"], f"{label}.migrationIds"))
    ]
    if migration_ids != list(dict.fromkeys(migration_ids)):
        fail(f"{label}.migrationIds must be unique and ordered")
    if any(
        not re.fullmatch(r"[a-z](?:[a-z0-9.-]{1,126}[a-z0-9])", value)
        for value in migration_ids
    ):
        fail(f"{label}.migrationIds contains an invalid stable ID")

    domains = expect_array(ledger["domains"], f"{label}.domains")
    if not domains:
        fail(f"{label}.domains must not be empty")
    domain_ids: list[str] = []
    used_paths: set[str] = set()
    used_adapter_paths: set[str] = set()
    used_migration_implementation_paths: set[str] = set()
    used_migrations: set[str] = set()
    for domain_index, raw_domain in enumerate(domains):
        expected_migration_bindings: set[tuple[str, int, str]] = set()
        domain_label = f"{label}.domains[{domain_index}]"
        domain = expect_object(raw_domain, domain_label)
        expect_exact_keys(
            domain,
            {
                "id",
                "owner",
                "versionField",
                "currentVersion",
                "migrationBaselineVersion",
                "minimumReadableVersion",
                "versionProbe",
                "currentCodecProbe",
                "migrationProbe",
                "versions",
                "transitions",
            },
            domain_label,
        )
        domain_id = expect_string(domain["id"], f"{domain_label}.id")
        if not re.fullmatch(r"[a-z][a-z0-9-]*", domain_id) or domain_id in domain_ids:
            fail(f"{domain_label}.id must be unique kebab-case")
        domain_ids.append(domain_id)
        if domain["owner"] != "magrathea-core":
            fail(f"{domain_label}.owner must be magrathea-core")
        if domain["versionField"] != "schemaVersion":
            fail(f"{domain_label}.versionField must be schemaVersion")
        current = expect_positive_int(domain["currentVersion"], f"{domain_label}.currentVersion")
        migration_baseline = expect_positive_int(
            domain["migrationBaselineVersion"],
            f"{domain_label}.migrationBaselineVersion",
        )
        minimum = expect_positive_int(
            domain["minimumReadableVersion"], f"{domain_label}.minimumReadableVersion"
        )
        if migration_baseline > current:
            fail(f"{domain_label}.migrationBaselineVersion must not exceed currentVersion")
        if minimum != migration_baseline:
            fail(
                f"{domain_label}.minimumReadableVersion must remain at the frozen "
                f"migration baseline {migration_baseline}, got {minimum}"
            )
        codec_patterns: list[str] = []
        if verify_sources_and_files:
            verify_regex_probe(
                root,
                domain["versionProbe"],
                {str(current)},
                f"{domain_label}.versionProbe",
            )
            codec_patterns = verify_codec_probe(
                root,
                domain["currentCodecProbe"],
                f"{domain_label}.currentCodecProbe",
            )
            verify_regex_probe(
                root,
                domain["migrationProbe"],
                set(migration_ids),
                f"{domain_label}.migrationProbe",
            )

        versions = expect_array(domain["versions"], f"{domain_label}.versions")
        version_numbers: list[int] = []
        current_adapter_symbol: Optional[str] = None
        for version_index, raw_version in enumerate(versions):
            version_label = f"{domain_label}.versions[{version_index}]"
            entry = expect_object(raw_version, version_label)
            expect_exact_keys(entry, {"version", "adapter", "fixtures"}, version_label)
            version = expect_positive_int(entry["version"], f"{version_label}.version")
            version_numbers.append(version)
            if verify_sources_and_files:
                adapter_symbol = verify_version_adapter(
                    root,
                    entry["adapter"],
                    version,
                    minimum,
                    used_adapter_paths,
                    used_paths,
                    f"{version_label}.adapter",
                )
                if version == current:
                    current_adapter_symbol = adapter_symbol
            fixtures = expect_array(entry["fixtures"], f"{version_label}.fixtures")
            if verify_sources_and_files:
                kinds = {
                    verify_fixture(
                        root,
                        fixture,
                        version,
                        used_paths,
                        f"{version_label}.fixtures[{fixture_index}]",
                    )
                    for fixture_index, fixture in enumerate(fixtures)
                }
                if kinds != {"session", "checkpoint"} or len(fixtures) != 2:
                    fail(f"{version_label} must freeze one session and one checkpoint fixture")
        if not version_numbers:
            fail(f"{domain_label}.versions must not be empty")
        expected_versions = list(range(version_numbers[0], current + 1))
        if version_numbers != expected_versions:
            fail(f"{domain_label}.versions must be contiguous through currentVersion")
        if verify_sources_and_files:
            if current_adapter_symbol is None:
                fail(f"{domain_label} current schema must declare a frozen adapter")
            required_bindings = {
                f"{current_adapter_symbol}.encodeSession",
                f"{current_adapter_symbol}.decodeSession",
                f"{current_adapter_symbol}.encodeCheckpoint",
                f"{current_adapter_symbol}.decodeCheckpoint",
            }
            if not required_bindings.issubset(codec_patterns):
                fail(
                    f"{domain_label}.currentCodecProbe must route every codec through "
                    f"{current_adapter_symbol}"
                )

        transitions = expect_array(domain["transitions"], f"{domain_label}.transitions")
        if len(transitions) != len(versions) - 1:
            fail(f"{domain_label} must declare every adjacent transition")
        for transition_index, raw_transition in enumerate(transitions):
            transition_label = f"{domain_label}.transitions[{transition_index}]"
            transition = expect_object(raw_transition, transition_label)
            expected_from = version_numbers[transition_index]
            expected_to = version_numbers[transition_index + 1]
            actual_from = expect_positive_int(transition.get("from"), f"{transition_label}.from")
            actual_to = expect_positive_int(transition.get("to"), f"{transition_label}.to")
            if (actual_from, actual_to) != (expected_from, expected_to):
                fail(f"{transition_label} must be exactly {expected_from}->{expected_to}")
            kind = transition.get("kind")
            if kind == "migration":
                expect_exact_keys(
                    transition,
                    {"from", "to", "kind", "migrationId", "implementation"},
                    transition_label,
                )
                migration_id = expect_string(transition["migrationId"], f"{transition_label}.migrationId")
                if migration_id not in migration_ids or migration_id in used_migrations:
                    fail(f"{transition_label}.migrationId is missing, unknown, or reused")
                used_migrations.add(migration_id)
                implementation_symbol = verify_migration_implementation(
                    root,
                    transition["implementation"],
                    used_migration_implementation_paths,
                    f"{transition_label}.implementation",
                    verify_sources_and_files,
                )
                expected_migration_bindings.add(
                    (migration_id, expected_from, implementation_symbol)
                )
            elif kind == "clean-break":
                if expected_from >= migration_baseline:
                    fail(
                        f"{transition_label} crosses the frozen migration baseline; "
                        "future transitions must be migrations"
                    )
                expect_exact_keys(
                    transition,
                    {"from", "to", "kind", "decision", "reason"},
                    transition_label,
                )
                reason = expect_string(transition["reason"], f"{transition_label}.reason")
                if len(reason.strip()) < 40:
                    fail(f"{transition_label}.reason must explain the data loss")
                if verify_sources_and_files:
                    verify_document_anchor(root, transition["decision"], f"{transition_label}.decision")
            else:
                fail(f"{transition_label}.kind must be migration or clean-break")

        readable = current
        for transition in reversed(transitions):
            if transition["kind"] != "migration":
                break
            readable = transition["from"]
        if minimum != readable:
            fail(f"{domain_label}.minimumReadableVersion must be {readable}, got {minimum}")

        if verify_sources_and_files:
            verify_migration_registry_bindings(
                root,
                domain["migrationProbe"],
                expected_migration_bindings,
                f"{domain_label}.migrationProbe",
            )

    ledger["descriptorRefreezes"] = validate_descriptor_refreezes(
        root,
        ledger.get("descriptorRefreezes", []),
        domains,
        f"{label}.descriptorRefreezes",
        verify_sources_and_files,
    )
    if set(migration_ids) != used_migrations:
        fail(f"{label}.migrationIds must exactly match migration transitions")
    return ledger


def verify_append_only(baseline: dict[str, Any], current: dict[str, Any]) -> None:
    for field in ("ledgerFormatVersion", "policyDocument"):
        if baseline[field] != current[field]:
            fail(f"append-only history changed {field}")
    old_migrations = baseline["migrationIds"]
    if current["migrationIds"][: len(old_migrations)] != old_migrations:
        fail("append-only migration ID history changed")
    old_refreezes = baseline["descriptorRefreezes"]
    current_refreezes = current["descriptorRefreezes"]
    if current_refreezes[: len(old_refreezes)] != old_refreezes:
        fail("append-only descriptor refreeze history changed")
    appended_refreezes = current_refreezes[len(old_refreezes) :]
    used_refreeze_ids: set[str] = set()
    current_domains = {domain["id"]: domain for domain in current["domains"]}
    for old_domain in baseline["domains"]:
        domain_id = old_domain["id"]
        new_domain = current_domains.get(domain_id)
        if new_domain is None:
            fail(f"append-only domain {domain_id!r} was removed")
        for field in (
            "owner",
            "versionField",
            "versionProbe",
            "migrationProbe",
            "migrationBaselineVersion",
            "minimumReadableVersion",
        ):
            if old_domain[field] != new_domain[field]:
                fail(f"append-only domain {domain_id!r} changed {field}")
        old_current = old_domain["currentVersion"]
        new_current = new_domain["currentVersion"]
        if new_current < old_current or new_current > old_current + 1:
            fail(f"domain {domain_id!r} must advance by at most one schema version per change")
        if new_current == old_current and old_domain["currentCodecProbe"] != new_domain["currentCodecProbe"]:
            fail(f"domain {domain_id!r} changed its codec binding without a schema version bump")
        old_versions = old_domain["versions"]
        expected_old_versions = copy.deepcopy(old_versions)
        for refreeze in appended_refreezes:
            if refreeze["domain"] != domain_id:
                continue
            version_entry = next(
                (
                    entry
                    for entry in expected_old_versions
                    if entry["version"] == refreeze["version"]
                ),
                None,
            )
            if version_entry is None:
                fail(
                    f"descriptor refreeze {refreeze['id']!r} does not target frozen baseline history"
                )
            adapter = version_entry.get("adapter")
            descriptor = adapter.get("descriptorFixture") if adapter else None
            if descriptor is None or descriptor.get("path") != refreeze["descriptorPath"]:
                fail(f"descriptor refreeze {refreeze['id']!r} targets a different descriptor")
            previous_digest = descriptor.get("sha256")
            if previous_digest == refreeze["fromSha256"]:
                descriptor["sha256"] = refreeze["toSha256"]
            elif previous_digest != refreeze["toSha256"]:
                fail(f"descriptor refreeze {refreeze['id']!r} has the wrong previous digest")
            used_refreeze_ids.add(refreeze["id"])
        if new_domain["versions"][: len(old_versions)] != expected_old_versions:
            fail(f"append-only domain {domain_id!r} changed frozen version/fixture history")
        old_transitions = old_domain["transitions"]
        if new_domain["transitions"][: len(old_transitions)] != old_transitions:
            fail(f"append-only domain {domain_id!r} changed transition history")
    unused_refreezes = [
        refreeze["id"]
        for refreeze in appended_refreezes
        if refreeze["id"] not in used_refreeze_ids
    ]
    if unused_refreezes:
        fail(f"descriptor refreezes do not authorize frozen baseline changes: {unused_refreezes}")


def run_contract_self_test(root: Path, ledger: dict[str, Any]) -> None:
    changed_fixture = copy.deepcopy(ledger)
    changed_fixture["domains"][0]["versions"][-1]["fixtures"][0]["sha256"] = "0" * 64
    try:
        verify_append_only(ledger, changed_fixture)
    except VerificationError:
        pass
    else:
        fail("self-test: append-only gate accepted a rewritten frozen fixture checksum")

    changed_adapter = copy.deepcopy(ledger)
    changed_adapter["domains"][0]["versions"][-1]["adapter"]["sha256"] = "0" * 64
    try:
        verify_append_only(ledger, changed_adapter)
    except VerificationError:
        pass
    else:
        fail("self-test: append-only gate accepted a rewritten frozen adapter checksum")

    changed_descriptor = copy.deepcopy(ledger)
    changed_descriptor["domains"][0]["versions"][-1]["adapter"]["descriptorFixture"][
        "sha256"
    ] = "0" * 64
    try:
        verify_append_only(ledger, changed_descriptor)
    except VerificationError:
        pass
    else:
        fail("self-test: append-only gate accepted an unauthorized descriptor refreeze")

    recorded_descriptor = copy.deepcopy(ledger)
    descriptor = recorded_descriptor["domains"][0]["versions"][-1]["adapter"][
        "descriptorFixture"
    ]
    previous_descriptor_digest = descriptor["sha256"]
    replacement_descriptor_digest = "0" * 64
    descriptor["sha256"] = replacement_descriptor_digest
    recorded_descriptor["descriptorRefreezes"].append(
        {
            "id": "self-test.descriptor-refreeze",
            "domain": recorded_descriptor["domains"][0]["id"],
            "version": recorded_descriptor["domains"][0]["versions"][-1]["version"],
            "descriptorPath": descriptor["path"],
            "fromSha256": previous_descriptor_digest,
            "toSha256": replacement_descriptor_digest,
            "decision": (
                "docs/adr/ADR-005-persistence-contracts.md"
                "#additive-enum-evolution-within-a-frozen-schema"
            ),
            "reason": (
                "Self-test only: persisted payloads remain unchanged under this exact refreeze."
            ),
        }
    )
    normalized_recorded_descriptor = validate_ledger(
        root,
        recorded_descriptor,
        "self-test recorded descriptor refreeze",
        verify_sources_and_files=False,
    )
    verify_append_only(ledger, normalized_recorded_descriptor)

    backfill_baseline = copy.deepcopy(ledger)
    backfill_baseline["descriptorRefreezes"] = backfill_baseline["descriptorRefreezes"][:-1]
    verify_append_only(backfill_baseline, ledger)

    wrong_previous_digest = copy.deepcopy(normalized_recorded_descriptor)
    wrong_previous_digest["descriptorRefreezes"][-1]["fromSha256"] = "1" * 64
    try:
        normalized_wrong_previous_digest = validate_ledger(
            root,
            wrong_previous_digest,
            "self-test descriptor refreeze with wrong previous digest",
            verify_sources_and_files=False,
        )
        verify_append_only(ledger, normalized_wrong_previous_digest)
    except VerificationError:
        pass
    else:
        fail("self-test: schema gate accepted a descriptor refreeze with the wrong prior digest")

    rewritten_refreeze = copy.deepcopy(ledger)
    rewritten_refreeze["descriptorRefreezes"][0]["reason"] += " Rewritten."
    try:
        verify_append_only(ledger, rewritten_refreeze)
    except VerificationError:
        pass
    else:
        fail("self-test: append-only gate accepted rewritten descriptor refreeze history")

    changed_codec = copy.deepcopy(ledger)
    changed_codec["domains"][0]["currentCodecProbe"]["requiredPatterns"].append(
        "unversioned-codec-change"
    )
    try:
        verify_append_only(ledger, changed_codec)
    except VerificationError:
        pass
    else:
        fail("self-test: append-only gate accepted a codec change without a schema bump")

    skipped_version = copy.deepcopy(ledger)
    skipped_version["domains"][0]["currentVersion"] += 2
    try:
        verify_append_only(ledger, skipped_version)
    except VerificationError:
        pass
    else:
        fail("self-test: append-only gate accepted a skipped schema version")

    advanced_minimum = copy.deepcopy(ledger)
    advanced_minimum["domains"][0]["minimumReadableVersion"] += 1
    try:
        verify_append_only(ledger, advanced_minimum)
    except VerificationError:
        pass
    else:
        fail("self-test: append-only gate accepted an advanced minimum readable version")

    future_clean_break = copy.deepcopy(ledger)
    domain = future_clean_break["domains"][0]
    previous_version = domain["currentVersion"]
    next_version = previous_version + 1
    domain["currentVersion"] = next_version
    domain["versions"].append(
        {
            "version": next_version,
            "adapter": None,
            "fixtures": [],
        }
    )
    domain["transitions"].append(
        {
            "from": previous_version,
            "to": next_version,
            "kind": "clean-break",
            "decision": "docs/adr/ADR-005-persistence-contracts.md#historical-clean-breaks",
            "reason": "Self-test only: a future clean break must never pass the migration baseline.",
        }
    )
    try:
        validate_ledger(
            root,
            future_clean_break,
            "self-test future clean-break ledger",
            verify_sources_and_files=False,
        )
    except VerificationError:
        pass
    else:
        fail("self-test: schema gate accepted a clean break after the migration baseline")

    future_missing_implementation = copy.deepcopy(ledger)
    domain = future_missing_implementation["domains"][0]
    previous_version = domain["currentVersion"]
    next_version = previous_version + 1
    migration_id = f"agent-storage.v{previous_version}-to-v{next_version}"
    future_missing_implementation["migrationIds"].append(migration_id)
    domain["currentVersion"] = next_version
    domain["versions"].append(
        {
            "version": next_version,
            "adapter": None,
            "fixtures": [],
        }
    )
    domain["transitions"].append(
        {
            "from": previous_version,
            "to": next_version,
            "kind": "migration",
            "migrationId": migration_id,
        }
    )
    try:
        validate_ledger(
            root,
            future_missing_implementation,
            "self-test future migration without implementation",
            verify_sources_and_files=False,
        )
    except VerificationError:
        pass
    else:
        fail("self-test: schema gate accepted a migration without a frozen implementation")

    future_migration = copy.deepcopy(future_missing_implementation)
    future_migration["domains"][0]["transitions"][-1]["implementation"] = {
        "path": "magrathea-core/src/commonMain/kotlin/saien/magrathea/core/FutureMigration.kt",
        "sha256": "1" * 64,
        "symbol": "AgentStorageV6ToV7Migration",
    }
    rewritten_implementation = copy.deepcopy(future_migration)
    rewritten_implementation["domains"][0]["transitions"][-1]["implementation"]["sha256"] = (
        "2" * 64
    )
    try:
        verify_append_only(future_migration, rewritten_implementation)
    except VerificationError:
        pass
    else:
        fail("self-test: append-only gate accepted a rewritten migration implementation")

    adapter_path = (
        "magrathea-core/src/commonMain/kotlin/saien/magrathea/core/StorageSchemaEvolution.kt"
    )
    adapter_digest = hashlib.sha256((root / adapter_path).read_bytes()).hexdigest()
    invalid_source_binding = {
        "path": adapter_path,
        "sha256": adapter_digest,
        "symbol": "MissingMigrationImplementation",
    }
    try:
        verify_migration_implementation(
            root,
            invalid_source_binding,
            set(),
            "self-test migration implementation",
            verify_source=True,
        )
    except VerificationError:
        pass
    else:
        fail("self-test: schema gate accepted a missing migration implementation symbol")

    for forbidden_source in (
        "StorageSchemaV7Adapter.decodeSession(json, document)",
        "AgentSessionSnapshot.serializer()",
        "AgentCheckpoint . serializer ()",
    ):
        try:
            verify_migration_source_isolation(
                forbidden_source,
                "self-test migration source isolation",
            )
        except VerificationError:
            pass
        else:
            fail("self-test: schema gate accepted a migration coupled to live schema behavior")

    redirected_registry = """
        RegisteredStorageSchemaMigration(
            id = "agent-storage.v6-to-v7",
            fromVersion = 6,
            migration = DifferentMigration,
        )
    """
    if migration_registry_binding_count(
        redirected_registry,
        "agent-storage.v6-to-v7",
        6,
        "AgentStorageV6ToV7Migration",
    ) != 0:
        fail("self-test: registry binding probe accepted a redirected migration implementation")


def parse_args() -> argparse.Namespace:
    default_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=default_root)
    parser.add_argument("--ledger", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    ledger_path = (args.ledger or root / "persistence/schema-ledger.json").resolve()
    try:
        current = validate_ledger(root, load_json(ledger_path, "schema ledger"), "schema ledger")
        if args.baseline:
            baseline_path = args.baseline.resolve()
            baseline = validate_ledger(
                root,
                load_json(baseline_path, "baseline schema ledger"),
                "baseline schema ledger",
                verify_sources_and_files=False,
            )
            verify_append_only(baseline, current)
        if args.self_test:
            run_contract_self_test(root, current)
    except VerificationError as error:
        print(f"Persistence schema verification failed: {error}", file=sys.stderr)
        return 1
    fixture_count = sum(
        len(version["fixtures"])
        for domain in current["domains"]
        for version in domain["versions"]
    )
    descriptor_count = sum(
        version["adapter"] is not None
        for domain in current["domains"]
        for version in domain["versions"]
    )
    print(
        f"Verified {len(current['domains'])} persistence domain, "
        f"{fixture_count} frozen envelope fixtures, serializer-descriptor fixtures="
        f"{descriptor_count}, descriptor-refreezes={len(current['descriptorRefreezes'])}, "
        "and append-only schema policy."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
