/** Copy any value as pretty JSON; returns false when the browser refuses. */
export async function copyJson(value: unknown): Promise<boolean> {
	try {
		await navigator.clipboard.writeText(JSON.stringify(value, null, 2));
		return true;
	} catch {
		return false;
	}
}
