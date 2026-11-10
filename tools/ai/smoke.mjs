import { spawn } from "node:child_process";
import { resolve } from "node:path";
import { setTimeout } from "node:timers/promises";

// Keep first-run server output private: a gateway may print generated local credentials.
const server = spawn(resolve("node_modules/.bin/omniroute"), ["serve", "--headless"], {
  detached: true,
  env: { ...process.env, PORT: "20128" },
  stdio: ["ignore", "pipe", "pipe"],
});
server.stdout.resume();
server.stderr.resume();
let exited = false;
let launchError;
server.once("exit", () => { exited = true; });
server.once("error", (error) => { launchError = error.code; });

try {
  const deadline = Date.now() + 90_000;
  let status;
  while (Date.now() < deadline) {
    if (launchError || exited) throw new Error("OmniRoute exited before the gateway became ready");
    try {
      const response = await fetch("http://127.0.0.1:20128/v1/models", {
        signal: AbortSignal.timeout(3_000),
        redirect: "manual",
      });
      status = response.status;
      await response.body?.cancel();
      if (status === 200 || status === 401) break;
    } catch {
      // Startup is asynchronous; retry only within this bounded readiness window.
    }
    await setTimeout(1_000);
  }
  if (status !== 200 && status !== 401) throw new Error("OmniRoute gateway readiness check timed out");
  console.log(`OmniRoute gateway responded HTTP ${status} on /v1/models; no inference request sent.`);
} finally {
  if (server.pid) {
    try { process.kill(-server.pid, "SIGTERM"); } catch { /* Already stopped. */ }
  }
}
