import type { Handle } from '@sveltejs/kit';

/**
 * `finance.fausto.ar` is the passkey rp id, and three paths on it belong to
 * the auth worker rather than to this app:
 *
 *   - `/.well-known/assetlinks.json` — Android reads it to accept the app's
 *     APK key hash as an origin for the rp id.
 *   - the Apple app site association (both the `.well-known` path and the
 *     legacy one Apple still probes).
 *   - `/desktop-pair` — the browser page the JVM app opens to run a WebAuthn
 *     ceremony it cannot run itself.
 *
 * A hostname can only belong to one Worker, so this app took the custom
 * domain and forwards those paths. The auth worker picks its app by request
 * hostname, which is `auth.fausto.ar` once proxied, so `?rp=` tells it which
 * rp id is being asked about.
 */
const AUTH = 'https://auth.fausto.ar';
const RP = 'finance.fausto.ar';

const PROXIED = new Set([
	'/.well-known/assetlinks.json',
	'/.well-known/apple-app-site-association',
	'/apple-app-site-association',
	'/desktop-pair'
]);

export const handle: Handle = async ({ event, resolve }) => {
	if (PROXIED.has(event.url.pathname)) {
		const target = new URL(event.url.pathname + event.url.search, AUTH);
		target.searchParams.set('rp', RP);
		const upstream = await fetch(target, { headers: event.request.headers });
		// Strip the upstream's content-encoding bookkeeping: the body arriving
		// here is already decoded, and assetlinks in particular must stay
		// identity-encoded for GMS Cronet to validate it.
		const headers = new Headers(upstream.headers);
		headers.delete('content-encoding');
		headers.delete('content-length');
		return new Response(upstream.body, { status: upstream.status, headers });
	}
	return resolve(event);
};
