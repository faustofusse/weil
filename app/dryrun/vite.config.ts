import { fileURLToPath } from 'node:url';
import adapter from '@sveltejs/adapter-cloudflare';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

// The compiled Kotlin/JS library: the *real* ingestion rules and the real
// reconciliation matcher, built by `npm run kotlin`
// (:app:sharedLogic:jsBrowserProductionLibraryDistribution). Aliased rather
// than copied so the inspector can never drift from the app's own logic.
const kotlin = fileURLToPath(
	new URL('../sharedLogic/build/dist/js/productionLibrary/sharedLogic.mjs', import.meta.url)
);

export default defineConfig({
	resolve: { alias: { $kotlin: kotlin } },
	plugins: [
		tailwindcss(),
		sveltekit({
			compilerOptions: {
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			adapter: adapter()
		})
	]
});
