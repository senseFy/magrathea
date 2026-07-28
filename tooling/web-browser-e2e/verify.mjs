import assert from "node:assert/strict";
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, resolve, sep } from "node:path";
import { chromium, firefox, webkit } from "playwright";

const [resourcesDirectory, jsDirectory, wasmDirectory] = process.argv.slice(2).map((value) =>
  value === undefined ? undefined : resolve(value),
);
if (!resourcesDirectory || !jsDirectory || !wasmDirectory) {
  throw new Error("Usage: node verify.mjs <resources-dir> <js-webpack-dir> <wasm-webpack-dir>");
}

const host = "127.0.0.1";
const port = 19080;
const gatewayBaseUrl = "http://127.0.0.1:18081";
const browserTypes = [
  ["chromium", chromium],
  ["firefox", firefox],
  ["webkit", webkit],
];
const targets = [
  ["js", jsDirectory],
  ["wasm", wasmDirectory],
];

const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url ?? "/", `http://${host}:${port}`);
    const components = decodeURIComponent(url.pathname).split("/").filter(Boolean);
    const target = targets.find(([name]) => name === components[0]);
    if (!target) {
      response.writeHead(404).end("not found");
      return;
    }
    const relativePath = components.slice(1).join("/") || "index.html";
    const root = relativePath === "index.html" || relativePath === "style.css"
      ? resourcesDirectory
      : target[1];
    const file = resolve(root, relativePath);
    if (file !== root && !file.startsWith(`${root}${sep}`)) {
      response.writeHead(400).end("invalid path");
      return;
    }
    if (!(await stat(file)).isFile()) {
      response.writeHead(404).end("not found");
      return;
    }
    if (target[0] === "wasm" && extname(file) === ".wasm") {
      // Keep a deterministic window between DOM load and Kotlin/Wasm runtime readiness.
      await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
    }
    response.writeHead(200, {
      "Cache-Control": "no-store",
      "Content-Type": contentType(file),
      "X-Content-Type-Options": "nosniff",
    });
    response.end(await readFile(file));
  } catch (error) {
    response.writeHead(error?.code === "ENOENT" ? 404 : 500).end("request failed");
  }
});

await new Promise((resolveListen, rejectListen) => {
  server.once("error", rejectListen);
  server.listen(port, host, resolveListen);
});

try {
  for (const [browserName, browserType] of browserTypes) {
    const browser = await browserType.launch({ headless: true });
    try {
      for (const [targetName] of targets) {
        const context = await browser.newContext();
        const page = await context.newPage();
        const failures = [];
        page.on("pageerror", (error) => failures.push(`pageerror: ${error.message}`));
        page.on("requestfailed", (request) => {
          if (request.url().startsWith(`http://${host}:${port}/`)) {
            failures.push(`requestfailed: ${request.method()} ${request.url()} ${request.failure()?.errorText ?? ""}`);
          }
        });
        await page.addInitScript(
          ({ baseUrl, authorization, csrf }) => {
            globalThis.MAGRATHEA_GATEWAY_BASE_URL = baseUrl;
            globalThis.MAGRATHEA_GATEWAY_AUTHORIZATION = authorization;
            globalThis.MAGRATHEA_GATEWAY_CSRF_TOKEN = csrf;
          },
          {
            baseUrl: gatewayBaseUrl,
            authorization: "Bearer e2e-browser-session",
            csrf: "e2e-csrf",
          },
        );
        try {
          process.stdout.write(`MAGRATHEA_WEB_BROWSER_START browser=${browserName} target=${targetName}\n`);
          await page.goto(`http://${host}:${port}/${targetName}/`, { waitUntil: "load", timeout: 30_000 });
          await page.waitForFunction(
            () => document.documentElement.getAttribute("data-magrathea-runtime") === "ready",
            undefined,
            { timeout: 60_000 },
          );
          await page.locator("#chat-input").fill(`cross-browser ${browserName} ${targetName}`);
          await page.getByRole("button", { name: "Send" }).click();
          await page.waitForFunction(
            () => document.querySelector("#chat-status")?.textContent === "completed",
            undefined,
            { timeout: 30_000 },
          );
          assert.match(await page.locator("#chat-transcript").innerText(), /gateway e2e answer/);

          await page.locator("#chat-input").fill(`hang ${browserName} ${targetName}`);
          await page.getByRole("button", { name: "Send" }).click();
          await page.waitForFunction(
            () => document.querySelector("#chat-status")?.textContent === "running",
            undefined,
            { timeout: 10_000 },
          );
          await page.getByRole("button", { name: "Cancel" }).click();
          await page.waitForFunction(
            () => document.querySelector("#chat-status")?.textContent === "cancelled",
            undefined,
            { timeout: 10_000 },
          );
          await page.getByRole("button", { name: "Close" }).click();
          await page.waitForTimeout(50);
          assert.deepEqual(failures, []);
          process.stdout.write(
            `MAGRATHEA_WEB_BROWSER_PASS browser=${browserName} version=${browser.version()} target=${targetName}\n`,
          );
        } catch (error) {
          const status = await page.locator("#chat-status").textContent().catch(() => "unavailable");
          const uiError = await page.locator("#chat-error").textContent().catch(() => "unavailable");
          const transcript = await page.locator("#chat-transcript").innerText().catch(() => "unavailable");
          const runtime = await page.locator("html").getAttribute("data-magrathea-runtime")
            .catch(() => "unavailable");
          throw new Error(
            `Cross-browser runtime failed for ${browserName}/${targetName}; runtime=${runtime}; status=${status}; ` +
              `uiError=${uiError}; failures=${JSON.stringify(failures)}; transcript=${transcript}`,
            { cause: error },
          );
        } finally {
          await context.close();
        }
      }
    } finally {
      await browser.close();
    }
  }
} finally {
  await new Promise((resolveClose, rejectClose) => {
    server.close((error) => (error ? rejectClose(error) : resolveClose()));
  });
}

function contentType(file) {
  switch (extname(file)) {
    case ".html": return "text/html; charset=utf-8";
    case ".css": return "text/css; charset=utf-8";
    case ".js": return "text/javascript; charset=utf-8";
    case ".map": return "application/json; charset=utf-8";
    case ".wasm": return "application/wasm";
    default: return "application/octet-stream";
  }
}
