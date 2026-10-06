import { cloudflare } from "@cloudflare/vite-plugin";
import { defineConfig } from "vite";

// Bundles the Worker for `cf build`/`cf deploy`; the Worker itself is
// configured in cloudflare.config.ts.
export default defineConfig({
	plugins: [cloudflare()],
});
