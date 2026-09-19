/**
 * Ambient declarations. Kept in a script (non-module) file on purpose:
 * `declare module` only introduces a new module from a file with no top-level
 * import/export.
 */

/** The compiled Kotlin/JS library, aliased in vite.config.ts. */
declare module '$kotlin' {
	export const DryRun: {
		getInstance(): {
			parseNotificationJson(rowJson: string): string | null | undefined;
			parseEmailJson(rowJson: string): string | null | undefined;
			emailPlainTextJs(raw: string): string;
			decodeMimeHeaderJs(raw: string): string;
			parseMoneyJson(raw: string): string | null | undefined;
			resolveAccountHintJson(
				hintsJson: string,
				commodity: string,
				accountsJson: string
			): string | null | undefined;
			accountPathsJson(accountsJson: string): string;
			buildInboxJson(
				notificationsJson: string,
				emailsJson: string,
				accountsJson: string,
				knownRefsJson: string
			): string;
			candidateToEventJson(
				candidateJson: string,
				sourceRef: string | null,
				fallbackOwnAccountId: string | null
			): string;
			movementToEventJson(movementJson: string, ownAccountId: string | null): string;
			dayIndexOf(epochMs: number): number;
			defaultPolicyJson(): string;
			matchAllJson(eventsJson: string, factsJson: string, policyJson: string | null): string;
		};
	};
}

/**
 * Minimal structural stand-ins for the Workers bindings used by the shared
 * `app/worker/src/import.ts`. Pulling in @cloudflare/workers-types wholesale
 * would replace the DOM `fetch`/`Response` the browser half of this app uses.
 */
declare interface D1PreparedStatement {
	bind(...values: unknown[]): D1PreparedStatement;
	first<T = unknown>(): Promise<T | null>;
	all<T = unknown>(): Promise<{ results: T[] }>;
	run(): Promise<unknown>;
}

declare interface D1Database {
	prepare(query: string): D1PreparedStatement;
	exec(query: string): Promise<unknown>;
}

declare interface R2Bucket {
	put(
		key: string,
		value: ArrayBuffer | ArrayBufferView | string | ReadableStream | null,
		options?: { httpMetadata?: { contentType?: string } }
	): Promise<unknown>;
	get(
		key: string
	): Promise<{ body: ReadableStream; httpMetadata?: { contentType?: string } } | null>;
}
