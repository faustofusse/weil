/** Minor units → "1.234,56", the app's own spelling. */
export function money(minor: number, commodity = 'ARS'): string {
	const sign = minor < 0 ? '-' : '';
	const abs = Math.abs(minor);
	const whole = Math.floor(abs / 100).toLocaleString('es-AR');
	const cents = String(abs % 100).padStart(2, '0');
	return `${sign}${whole},${cents} ${commodity}`;
}

export function day(epochMs: number): string {
	return new Date(epochMs).toISOString().slice(0, 10);
}

export function dateTime(epochMs: number): string {
	return new Date(epochMs).toLocaleString('es-AR');
}
