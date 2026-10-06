import { bindings, defineConfig } from "cf/config";

import * as entrypoint from "./src/index.ts" with { type: "cf-worker" };

// The only config of this Worker. The two jobs `cf` cannot do yet go through
// Wrangler by Worker name (fetched by npx; it is not a dependency):
// `npm run tail` and `npm run secret -- <NAME>`.
export default defineConfig({
	accountId: "f12da7851e4dd1d107a80417a1d4cbbd",
	worker: {
		name: "finance",
		entrypoint,
		compatibilityDate: "2025-12-01",
		compatibilityFlags: ["nodejs_compat"],
		workersDev: true,
		domains: ["api.finance.fausto.ar"],
		env: {
			TURSO_ORG: bindings.text("faustofusse"),
			APP_SLUG: bindings.text("finance"),
			FORWARD_TO: bindings.text("faustofusse@gmail.com"),
			ACCOUNT_ID: bindings.text("f12da7851e4dd1d107a80417a1d4cbbd"),
			AI_GATEWAY: bindings.text("finance"),
			GEMINI_MODELS: bindings.text("gemini-3.5-flash,gemini-3.6-flash,gemini-3.5-flash-lite"),
			// wa-bridge (app/wa-bridge): the WhatsApp socket cannot live on Workers.
			BRIDGE_URL: bindings.text("https://weil-wa-bridge.fly.dev"),
			WHATSAPP_NUMBER: bindings.text("5491178265131"),
			// The shared auth D1, to verify the session cookie.
			AUTH_DB: bindings.d1({
				name: "auth",
				id: "b6532fe4-d2a1-42b2-b783-00d97405614b",
			}),
			DOCS: bindings.r2({ name: "finance-docs" }),
			// Workers AI: the second reader for a captured message (GLM).
			AI: bindings.ai({}),
			// Set with `npm run secret -- <NAME>`; declared so they are typed.
			// `secret()` means required, so the optional AI_GATEWAY_TOKEN (unset
			// today, read through `ImportEnv`) is deliberately not listed.
			TURSO_API_TOKEN: bindings.secret(),
			GEMINI_API_KEY: bindings.secret(),
			TYPESAFE_API_KEY: bindings.secret(),
			BRIDGE_SECRET: bindings.secret(),
		},
	},
});
