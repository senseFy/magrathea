@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package saien.magrathea.storage.web

import kotlin.js.JsString
import kotlin.js.Promise

internal const val WEB_CATALOG_SEARCH_SCAN_LIMIT = 128

/**
 * Internal IndexedDB mechanics only. Kotlin owns projection, activity, identity and maintenance
 * policy. Long values cross this boundary as strings. Only revision increment is performed here,
 * using decimal digits so concurrent commits can retain unrelated control fields without precision loss.
 * All success responses wait for transaction completion. An exception after writes were queued
 * aborts the transaction before a failure is returned.
 */
internal fun runCatalogIndexedDbOperation(
    databaseName: String,
    operation: String,
    payload: String,
): Promise<JsString> = js(
    """
    new Promise((resolve) => {
      const stores = ["sessions", "checkpoints", "session_catalog", "session_search",
        "session_attachment_refs", "session_collections", "session_organization", "catalog_state"];
      const owns = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
      const isObject = (value) => value !== null && typeof value === "object" &&
        (Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null);
      const patchValue = (value, fields) => {
        if (!isObject(value) || !isObject(fields)) throw new Error("Invalid internal patch");
        const updated = Object.create(null);
        for (const key of Object.keys(value)) updated[key] = value[key];
        for (const key of Object.keys(fields)) updated[key] = fields[key];
        return updated;
      };
      let settled = false;
      const finish = (ok, code, result) => {
        if (settled) return;
        settled = true;
        try {
          resolve(JSON.stringify({ ok: ok, code: code || null,
            payload: result === undefined ? null : JSON.stringify(result) }));
        } catch (_) {
          // IndexedDB can contain structured-clone values such as cycles and BigInt. Their
          // unrepresentable contents must never escape or leave the owning handle awaiting forever.
          resolve(JSON.stringify({ ok: false, code: "operation_failed", payload: null }));
        }
      };
      const classify = (error) => {
        const name = error && typeof error.name === "string" ? error.name : "";
        if (name === "CatalogCounterExhaustedError") return "catalog_counter_exhausted";
        if (name === "QuotaExceededError") return "quota_exceeded";
        if (name === "VersionError" || name === "NotFoundError") return "unsupported_database_version";
        if (name === "SecurityError" || name === "InvalidStateError" || name === "NotSupportedError") return "unavailable";
        return "operation_failed";
      };
      const same = (first, second) => {
        if (first === second) return true;
        if (first === null || second === null || typeof first !== "object" || typeof second !== "object") return false;
        if (Array.isArray(first) !== Array.isArray(second)) return false;
        const left = Object.keys(first);
        const right = Object.keys(second);
        return left.length === right.length && left.every((key) => owns(second, key) && same(first[key], second[key]));
      };
      const nextRevision = (value) => {
        if (typeof value !== "string" || !/^(0|[1-9][0-9]*)$/.test(value) ||
            value.length > 19 || (value.length === 19 && value > "9223372036854775807")) {
          throw new Error("Invalid internal revision");
        }
        if (value === "9223372036854775807") {
          const error = new Error("Catalog counter exhausted");
          error.name = "CatalogCounterExhaustedError";
          throw error;
        }
        const digits = value.split("");
        for (let position = digits.length - 1; position >= 0; position -= 1) {
          if (digits[position] !== "9") {
            digits[position] = String(Number(digits[position]) + 1);
            return digits.join("");
          }
          digits[position] = "0";
        }
        return "1" + digits.join("");
      };
      const validStore = (name) => {
        if (!stores.includes(name)) throw new Error("Invalid internal store");
        return name;
      };
      // Physical maintenance cursors must retain the IndexedDB key type, including legacy
      // non-string keys. Numeric strings also preserve the valid +/-Infinity number keys.
      const keyToken = (key) => {
        if (typeof key === "number") return ["n", String(key)];
        if (typeof key === "string") return ["s", key];
        if (key instanceof Date) return ["d", String(key.getTime())];
        if (key instanceof ArrayBuffer) return ["b", Array.from(new Uint8Array(key))];
        if (ArrayBuffer.isView(key)) return ["b", Array.from(new Uint8Array(key.buffer, key.byteOffset, key.byteLength))];
        if (Array.isArray(key)) return ["a", key.map(keyToken)];
        throw new Error("Invalid internal physical key");
      };
      const tokenKey = (token) => {
        if (!Array.isArray(token) || token.length !== 2) throw new Error("Invalid internal key token");
        const value = token[1];
        if (token[0] === "s" && typeof value === "string") return value;
        if ((token[0] === "n" || token[0] === "d") && typeof value === "string" && value.length > 0) {
          const number = Number(value);
          if (!Number.isNaN(number)) {
            if (token[0] === "n") return number;
            const date = new Date(number);
            if (!Number.isNaN(date.getTime())) return date;
          }
        }
        if (token[0] === "b" && Array.isArray(value) && value.every((byte) => Number.isInteger(byte) && byte >= 0 && byte <= 255)) {
          return Uint8Array.from(value).buffer;
        }
        if (token[0] === "a" && Array.isArray(value)) return value.map(tokenKey);
        throw new Error("Invalid internal key token");
      };
      const keyRange = (range) => {
        if (range === undefined || range === null) return null;
        if (!isObject(range)) throw new Error("Invalid internal key range");
        if (owns(range, "onlyToken")) return IDBKeyRange.only(tokenKey(range.onlyToken));
        if (owns(range, "only")) return IDBKeyRange.only(range.only);
        const lower = owns(range, "lowerToken") ? tokenKey(range.lowerToken) : range.lower;
        const upper = owns(range, "upperToken") ? tokenKey(range.upperToken) : range.upper;
        if (lower !== undefined && upper !== undefined) {
          return IDBKeyRange.bound(lower, upper, range.lowerOpen === true, range.upperOpen === true);
        }
        if (lower !== undefined) return IDBKeyRange.lowerBound(lower, range.lowerOpen === true);
        if (upper !== undefined) return IDBKeyRange.upperBound(upper, range.upperOpen === true);
        return null;
      };
      if (!globalThis.indexedDB) { finish(false, "unavailable"); return; }
      let input;
      let open;
      let openFailureCode = null;
      try {
        input = JSON.parse(payload);
        if (!isObject(input) || !["read", "keys", "page", "bulkSessions", "referenceLookup", "atomicWrite"].includes(operation)) {
          throw new Error("Invalid internal operation");
        }
        open = globalThis.indexedDB.open(databaseName, 3);
      } catch (error) { finish(false, classify(error)); return; }
      open.onblocked = () => finish(false, "blocked");
      open.onerror = () => finish(false, openFailureCode || classify(open.error));
      open.onupgradeneeded = (event) => {
        if (settled || ![0, 1, 2].includes(event.oldVersion)) {
          openFailureCode = "unsupported_database_version";
          try { open.transaction.abort(); } catch (_) {}
          return;
        }
        try {
          const database = open.result;
          if (event.oldVersion === 2) {
            // Fence v2 writers: their control serializer drops fields it does not know.
            // Only control changes in v3; canonical records and all eight stores stay intact.
            const control = open.transaction.objectStore("catalog_state");
            const request = control.get("state");
            request.onsuccess = () => {
              try {
                if (request.result !== undefined) {
                  if (!isObject(request.result)) throw new Error("Invalid stored internal state");
                  control.put(patchValue(request.result, { collectionsRevision: "0" }), "state");
                }
              } catch (error) {
                openFailureCode = classify(error);
                try { open.transaction.abort(); } catch (_) { finish(false, openFailureCode); }
              }
            };
            return;
          }
          for (const name of stores) {
            if (!database.objectStoreNames.contains(name)) database.createObjectStore(name);
          }
          const index = (store, name, path, unique) => {
            const source = open.transaction.objectStore(store);
            if (!source.indexNames.contains(name)) source.createIndex(name, path, { unique: unique === true });
          };
          index("sessions", "metadata", "metadataKey", true);
          index("sessions", "recovery", "recoveryKey", false);
          index("session_catalog", "global_pin", ["pinBucket", "pinRank", "activityRank", "idSortKey"]);
          index("session_catalog", "global_activity", ["pinBucket", "activityRank", "idSortKey"]);
          index("session_catalog", "collection_pin", ["collectionKey", "pinBucket", "pinRank", "activityRank", "idSortKey"]);
          index("session_catalog", "collection_activity", ["collectionKey", "pinBucket", "activityRank", "idSortKey"]);
          index("session_catalog", "collection", "collectionKey");
          index("session_collections", "name", ["nameSortKey", "idSortKey"]);
          index("session_collections", "uniqueName", "nameUniqueKey", true);
          index("session_organization", "collection", "collectionId");
          index("session_attachment_refs", "session", "sessionId");
          index("session_attachment_refs", "uri", "uri");
        } catch (error) {
          openFailureCode = classify(error);
          try { open.transaction.abort(); } catch (_) { finish(false, openFailureCode); }
        }
      };
      open.onsuccess = () => {
        const database = open.result;
        database.onversionchange = () => database.close();
        if (settled) { database.close(); return; }
        if (database.version !== 3) { database.close(); finish(false, "unsupported_database_version"); return; }
        let transaction;
        let result;
        let failureCode = null;
        let nextState;
        const abort = (error) => {
          if (!failureCode) failureCode = classify(error);
          if (transaction) {
            try { transaction.abort(); return; } catch (_) {}
          }
          database.close();
          finish(false, failureCode);
        };
        const guarded = (action) => () => {
          if (settled || failureCode) return;
          try { action(); } catch (error) { abort(error); }
        };
        const source = (spec) => {
          const store = transaction.objectStore(validStore(spec.store));
          return spec.index === undefined || spec.index === null ? store : store.index(spec.index);
        };
        const list = (value) => {
          if (value === undefined) return [];
          if (!Array.isArray(value)) throw new Error("Invalid internal request list");
          return value;
        };
        const readMetadata = (id, accept) => {
          if (typeof id !== "string") throw new Error("Invalid internal metadata identity");
          const store = transaction.objectStore("sessions");
          const request = store.index("metadata").openKeyCursor(IDBKeyRange.bound([id], [id, []], false, true));
          request.onsuccess = guarded(() => {
            const cursor = request.result;
            if (cursor) {
              if (cursor.primaryKey !== id) throw new Error("Invalid internal metadata identity");
              accept({ id: id, found: true, key: cursor.key, checkpointFound: cursor.key[7] === 1 });
            } else {
              const existing = store.getKey(id);
              existing.onsuccess = guarded(() => {
                if (existing.result === undefined) accept({ id: id, found: false, key: null, checkpointFound: false });
                else {
                  const checkpoint = transaction.objectStore("checkpoints").getKey(id);
                  checkpoint.onsuccess = guarded(() => {
                    accept({ id: id, found: true, key: null, checkpointFound: checkpoint.result !== undefined });
                  });
                }
              });
            }
          });
        };
        const putAndPatch = () => {
          for (const item of list(input.puts)) {
            const store = transaction.objectStore(validStore(item.store));
            if (item.preserveAttachmentNameBackup === true && item.store === "sessions" && isObject(item.value)) {
              const request = store.get(item.key);
              request.onsuccess = guarded(() => {
                const old = request.result;
                const backup = isObject(old) ? old.attachmentNameBackup : undefined;
                store.put(backup === undefined ? item.value : patchValue(item.value, { attachmentNameBackup: backup }), item.key);
              });
            } else store.put(item.value, item.key);
          }
          for (const item of list(input.patches)) {
            const store = transaction.objectStore(validStore(item.store));
            if (!isObject(item.fields)) throw new Error("Invalid internal patch fields");
            if (owns(item, "key")) {
              const request = store.get(item.key);
              request.onsuccess = guarded(() => {
                if (request.result !== undefined) store.put(patchValue(request.result, item.fields), item.key);
              });
            } else {
              const request = source(item).openCursor(keyRange(item.range));
              request.onsuccess = guarded(() => {
                const cursor = request.result;
                if (cursor) {
                  store.put(patchValue(cursor.value, item.fields), cursor.primaryKey);
                  cursor.continue();
                }
              });
            }
          }
          if (owns(input, "state")) {
            if (!isObject(input.state)) throw new Error("Invalid internal state");
            transaction.objectStore("catalog_state").put(nextState, "state");
          }
          result = true;
        };
        const mutations = () => {
          for (const name of list(input.clears)) transaction.objectStore(validStore(name)).clear();
          for (const item of list(input.deletes)) transaction.objectStore(validStore(item.store)).delete(item.key);
          const ranges = list(input.deleteRanges);
          if (ranges.length === 0) { putAndPatch(); return; }
          let remaining = ranges.length;
          for (const item of ranges) {
            const store = transaction.objectStore(validStore(item.store));
            const request = source(item).openKeyCursor(keyRange(item.range));
            request.onsuccess = guarded(() => {
              const cursor = request.result;
              if (cursor) {
                const key = cursor.primaryKey;
                if (item.sourceKey === undefined || (Array.isArray(key) && (key[2] === undefined ? "session" : key[2]) === item.sourceKey)) {
                  store.delete(key);
                }
                cursor.continue();
              }
              else {
                remaining -= 1;
                if (remaining === 0) putAndPatch();
              }
            });
          }
        };
        const execute = (stateValue) => {
          if (stateValue !== undefined && !isObject(stateValue)) throw new Error("Invalid stored internal state");
          const state = stateValue === undefined ? null : stateValue;
          if (operation === "atomicWrite") {
            if (!owns(input, "expectedState")) throw new Error("Missing internal state expectation");
            if (input.rebaseRevision === true) {
              if (!isObject(input.expectedState) || !isObject(input.state)) throw new Error("Invalid internal revision plan");
              const fences = ["policyId", "policyVersion", "activityMode", "generation", "migrationLifecycle"];
              if (!isObject(stateValue) || !fences.every((key) => same(input.expectedState[key], stateValue[key]))) {
                result = false;
                return;
              }
              // This opt-in is only for canonical commits. All authority/progress writes retain
              // strict CAS, and a caller cannot smuggle control changes through this merge.
              const planned = patchValue(input.expectedState, { revision: nextRevision(input.expectedState.revision) });
              if (!same(planned, input.state)) throw new Error("Invalid internal revision-only plan");
              nextState = patchValue(stateValue, { revision: nextRevision(stateValue.revision) });
            } else {
              const unchanged = input.expectedState === null ? stateValue === undefined : same(input.expectedState, stateValue);
              if (!unchanged) { result = false; return; }
              nextState = input.state;
            }
            const checks = list(input.checks);
            if (checks.length === 0) { mutations(); return; }
            let remaining = checks.length;
            let matches = true;
            for (const item of checks) {
              if (owns(item, "expectedMetadata")) {
                if (item.store !== "sessions") throw new Error("Invalid metadata expectation store");
                readMetadata(item.key, (actual) => {
                  matches = matches && same(item.expectedMetadata, actual);
                  remaining -= 1;
                  if (remaining === 0) { if (matches) mutations(); else result = false; }
                });
                continue;
              }
              if (owns(item, "expectedNextKeyToken")) {
                const after = item.afterKeyToken === undefined || item.afterKeyToken === null ? undefined : tokenKey(item.afterKeyToken);
                const expected = item.expectedNextKeyToken === null ? undefined : tokenKey(item.expectedNextKeyToken);
                if (expected !== undefined && after !== undefined && indexedDB.cmp(expected, after) <= 0) {
                  matches = false;
                }
                const request = transaction.objectStore(validStore(item.store)).openKeyCursor(
                  after === undefined ? null : IDBKeyRange.lowerBound(after, true));
                request.onsuccess = guarded(() => {
                  const cursor = request.result;
                  matches = matches && (expected === undefined ? cursor === null :
                    cursor === null || indexedDB.cmp(cursor.primaryKey, expected) >= 0);
                  remaining -= 1;
                  if (remaining === 0) { if (matches) mutations(); else result = false; }
                });
                continue;
              }
              if (!owns(item, "expectedValue") && !owns(item, "expectedPayload") && !owns(item, "expectedFound")) throw new Error("Missing internal record expectation");
              if (owns(item, "expectedFound") && typeof item.expectedFound !== "boolean") throw new Error("Invalid internal existence expectation");
              const request = transaction.objectStore(validStore(item.store)).get(item.key);
              request.onsuccess = guarded(() => {
                const actual = request.result;
                const explicitExistence = owns(item, "expectedFound");
                if (explicitExistence) matches = matches && (item.expectedFound === (actual !== undefined));
                if (owns(item, "expectedValue")) {
                  matches = matches && (explicitExistence
                    ? (!item.expectedFound || same(item.expectedValue, actual))
                    : (item.expectedValue === null ? actual === undefined : same(item.expectedValue, actual)));
                }
                if (owns(item, "expectedPayload")) {
                  const storedPayload = typeof actual === "string" ? actual : isObject(actual) ? actual.payload : undefined;
                  matches = matches && (explicitExistence
                    ? (!item.expectedFound || storedPayload === item.expectedPayload)
                    : (item.expectedPayload === null ? actual === undefined : storedPayload === item.expectedPayload));
                }
                remaining -= 1;
                if (remaining === 0) { if (matches) mutations(); else result = false; }
              });
            }
            return;
          }
          if (operation === "read") {
            const lookups = list(input.lookups);
            const ids = list(input.metadataIds);
            const values = new Array(lookups.length);
            const metadata = new Array(ids.length);
            result = { state: state, values: values, metadata: metadata };
            lookups.forEach((item, position) => {
              const request = transaction.objectStore(validStore(item.store)).get(item.key);
              request.onsuccess = guarded(() => {
                values[position] = { store: item.store, key: item.key, found: request.result !== undefined,
                  value: request.result === undefined ? null : request.result };
              });
            });
            ids.forEach((id, position) => readMetadata(id, (value) => { metadata[position] = value; }));
            return;
          }
          if (operation === "bulkSessions") {
            // Explicit legacy bulk API: one cursor owns the full snapshot. Return only the raw
            // payload shape consumed by Kotlin, even for structured-clone corruption sources.
            const rows = [];
            result = { state: state, rows: rows };
            const request = transaction.objectStore("sessions").openCursor();
            request.onsuccess = guarded(() => {
              const cursor = request.result;
              if (!cursor) return;
              const value = cursor.value;
              const payload = typeof value === "string" ? value : isObject(value) && typeof value.payload === "string" ? value.payload : null;
              rows.push({ key: typeof cursor.primaryKey === "string" ? cursor.primaryKey : null, payload: payload });
              cursor.continue();
            });
            return;
          }
          if (operation === "referenceLookup") {
            const candidates = list(input.candidates);
            const found = [];
            result = { state: state, found: found };
            const index = transaction.objectStore("session_attachment_refs").index("uri");
            for (const uri of candidates) {
              if (typeof uri !== "string") throw new Error("Invalid internal reference candidate");
              const request = index.getKey(uri);
              request.onsuccess = guarded(() => { if (request.result !== undefined) found.push(uri); });
            }
            return;
          }
          if (!Number.isSafeInteger(input.limit) || input.limit < 1) throw new Error("Invalid internal page limit");
          const direction = input.direction === undefined ? "next" : input.direction;
          if (direction !== "next" && direction !== "prev") throw new Error("Invalid internal page direction");
          const rows = [];
          const searching = operation === "page" && input.search !== undefined && input.search !== null;
          // Keep this literal aligned with WEB_CATALOG_SEARCH_SCAN_LIMIT, also used by contract tests.
          const scanLimit = searching ? (input.scanLimit === undefined ? 128 : input.scanLimit) : Infinity;
          if (searching && (!Number.isSafeInteger(scanLimit) || scanLimit < 1 || scanLimit > 128)) throw new Error("Invalid internal scan limit");
          let scanned = 0;
          result = operation === "keys" ? { state: state, keys: rows }
            : { state: state, values: rows, lastScannedKey: null, scanExhausted: true };
          const request = operation === "keys" ? source(input).openKeyCursor(keyRange(input.range), direction)
            : source(input).openCursor(keyRange(input.range), direction);
          request.onsuccess = guarded(() => {
            const cursor = request.result;
            if (!cursor) return;
            if (operation === "page") { scanned += 1; result.lastScannedKey = cursor.key; }
            const append = (matches) => {
              if (matches) rows.push(operation === "keys"
                ? { key: cursor.key, primaryKey: cursor.primaryKey, primaryKeyToken: keyToken(cursor.primaryKey) }
                : cursor.value);
              if (rows.length < input.limit && scanned < scanLimit) cursor.continue();
              else if (operation === "page") result.scanExhausted = false;
            };
            if (searching) {
              const search = input.search;
              if (typeof search.needle !== "string" || !Array.isArray(search.fields) || !search.fields.every((field) => typeof field === "string")) {
                throw new Error("Invalid internal search");
              }
              const match = transaction.objectStore(validStore(search.store)).get(cursor.primaryKey);
              match.onsuccess = guarded(() => {
                const value = match.result;
                append(isObject(value) && search.fields.some((field) => typeof value[field] === "string" && value[field].includes(search.needle)));
              });
            } else append(true);
          });
        };
        try {
          const readStores = operation === "bulkSessions" ? ["sessions", "catalog_state"]
            : operation === "referenceLookup" ? ["session_attachment_refs", "catalog_state"] : stores;
          transaction = operation === "atomicWrite"
            ? database.transaction(stores, "readwrite", { durability: "strict" })
            : database.transaction(readStores, "readonly");
          transaction.oncomplete = () => { database.close(); finish(true, null, result); };
          transaction.onerror = () => { if (!failureCode) failureCode = classify(transaction.error); };
          transaction.onabort = () => { database.close(); finish(false, failureCode || classify(transaction.error)); };
          const state = transaction.objectStore("catalog_state").get("state");
          state.onsuccess = guarded(() => execute(state.result));
        } catch (error) { abort(error); }
      };
    })
    """,
)
