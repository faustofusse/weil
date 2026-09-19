declare global {
	namespace App {
		interface Platform {
			env: {
				/** Shared auth D1: session-cookie verification only, never written. */
				AUTH_DB: D1Database;
				APP_SLUG: string;
				TURSO_ORG: string;
				ACCOUNT_ID: string;
				AI_GATEWAY: string;
				GEMINI_MODELS: string;
				GEMINI_API_KEY: string;
			};
		}
	}
}

export {};
