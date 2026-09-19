import { startAuthentication } from '@simplewebauthn/browser';
import type {
	AuthenticationResponseJSON,
	PublicKeyCredentialRequestOptionsJSON
} from '@simplewebauthn/browser';

/**
 * Passkey sign-in against the shared auth worker, trimmed from
 * /Users/fausto/sw/gim/web/src/lib/auth.svelte.ts.
 *
 * One deliberate omission: there is **no register fallback**. The app's client
 * registers a user when login fails; a dry-run inspector must never create a
 * finance account by accident, so a failed login is an error here.
 *
 * `rp_id` for the finance app is `finance.fausto.ar`, and this SPA is served
 * from `dry.finance.fausto.ar` — a subdomain, so the passkeys that already
 * exist on the phone and the Mac work unchanged.
 */
const AUTH = 'https://auth.fausto.ar';
const APP = 'finance';
const STORAGE_KEY = 'weil.dryrun.session.v1';

/** Reuse a stored token only while it has this much life left. */
const REUSE_MARGIN = 24 * 60 * 60 * 1000;

export type Token = {
	jwt: string;
	db_url: string;
	db_hostname: string;
	expires_in: number;
};

export type Session = {
	user_id: string;
	credential_id: string;
	jwt: string;
	db_url: string;
	expires_at: number;
};

export class AuthError extends Error {}

type Phase = 'booting' | 'signing-in' | 'signed-out' | 'ready';

class AuthState {
	phase = $state<Phase>('booting');
	error = $state<string | null>(null);
	status = $state('');
	session = $state<Session | null>(null);

	get signedIn(): boolean {
		return this.phase === 'ready' && this.session !== null;
	}

	private async req<T>(path: string, body?: unknown): Promise<T> {
		const res = await fetch(`${AUTH}/apps/${APP}${path}`, {
			method: body === undefined ? 'GET' : 'POST',
			credentials: 'include', // sends/accepts the auth_finance cookie on .fausto.ar
			headers: body === undefined ? undefined : { 'content-type': 'application/json' },
			body: body === undefined ? undefined : JSON.stringify(body)
		});
		const data = await res.json().catch(() => ({}));
		if (!res.ok) throw new AuthError(String((data as { error?: string }).error ?? res.statusText));
		return data as T;
	}

	/** Restore from storage or the refresh cookie; never prompts. */
	async boot(): Promise<void> {
		const stored = readStored();
		if (stored && stored.expires_at > Date.now() + REUSE_MARGIN) {
			this.session = stored;
			this.phase = 'ready';
			return;
		}
		try {
			this.status = 'checking session…';
			const { token } = await this.req<{ token: Token }>('/session/refresh', {});
			this.session = store(stored?.user_id ?? '', stored?.credential_id ?? '', token);
			this.phase = 'ready';
		} catch {
			this.phase = 'signed-out';
		} finally {
			this.status = '';
		}
	}

	async signIn(): Promise<void> {
		this.error = null;
		this.phase = 'signing-in';
		try {
			this.status = 'waiting for passkey…';
			const { options } = await this.req<{ options: PublicKeyCredentialRequestOptionsJSON }>(
				'/login/start',
				{}
			);
			const response = await startAuthentication({ optionsJSON: options });
			const d = await this.req<{ user_id: string; credential_id: string; token: Token }>(
				'/login/finish',
				{ response: response satisfies AuthenticationResponseJSON }
			);
			this.session = store(d.user_id, d.credential_id, d.token);
			this.phase = 'ready';
		} catch (e) {
			this.error = describe(e);
			this.phase = 'signed-out';
		} finally {
			this.status = '';
		}
	}

	/** Fresh Turso token when the JWT starts being rejected (7-day life). */
	async refresh(): Promise<Session | null> {
		try {
			const { token } = await this.req<{ token: Token }>('/session/refresh', {});
			this.session = store(this.session?.user_id ?? '', this.session?.credential_id ?? '', token);
			return this.session;
		} catch {
			return null;
		}
	}

	async signOut(): Promise<void> {
		await this.req('/logout', {}).catch(() => {});
		localStorage.removeItem(STORAGE_KEY);
		this.session = null;
		this.phase = 'signed-out';
	}
}

function store(user_id: string, credential_id: string, token: Token): Session {
	const session: Session = {
		user_id,
		credential_id,
		jwt: token.jwt,
		db_url: token.db_url,
		expires_at: Date.now() + token.expires_in * 1000
	};
	localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
	return session;
}

function readStored(): Session | null {
	try {
		const raw = localStorage.getItem(STORAGE_KEY);
		if (!raw) return null;
		const s = JSON.parse(raw) as Session;
		return s.jwt && s.db_url ? s : null;
	} catch {
		return null;
	}
}

/** null means the user backed out of the passkey sheet — say nothing. */
function describe(e: unknown): string | null {
	if (e instanceof DOMException && (e.name === 'NotAllowedError' || e.name === 'AbortError'))
		return null;
	if (e instanceof Error) return e.message;
	return 'sign-in failed';
}

export const auth = new AuthState();
