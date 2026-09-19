<script lang="ts">
	import Json from '$lib/components/Json.svelte';
	import Panel from '$lib/components/Panel.svelte';
	import { auth } from '$lib/auth.svelte';
	import { ReadOnlyDb } from '$lib/db';
	import { candidateToEvent, type CandidateEvent, type ImportCandidate, type Outcome } from '$lib/kotlin';
	import { action, reconcile } from '$lib/reconcile';
	import { dateTime, day, money } from '$lib/format';

	type Analysis = {
		docSha256: string;
		mimeType: string;
		bytes: number;
		kind: 'document' | 'table';
		accounts: Array<{ id: string; name: string; path: string; type: string }>;
		history: Array<{ payee: string; path: string; count: number }>;
		promptText: string;
		schema: unknown;
		request: { parts: Array<Record<string, unknown>>; generationConfig: unknown };
		model?: string;
		viaGateway?: boolean;
		latencyMs?: number;
		usage?: Record<string, unknown>;
		rawText?: string;
		rawTransactions: unknown[];
		transactions: ImportCandidate[];
	};

	let file = $state<File | null>(null);
	let busy = $state(false);
	let error = $state<string | null>(null);
	let result = $state<Analysis | null>(null);
	let events = $state<CandidateEvent[]>([]);
	let outcomes = $state<Outcome[]>([]);
	let factCount = $state(0);
	let matching = $state(false);
	/** The review screen's screen-wide default asset account. */
	let fallbackAccount = $state<string | null>(null);
	let dragging = $state(false);

	const MIME: Record<string, string> = {
		csv: 'text/csv',
		pdf: 'application/pdf',
		jpg: 'image/jpeg',
		jpeg: 'image/jpeg',
		png: 'image/png',
		webp: 'image/webp',
		heic: 'image/heic'
	};

	function contentType(f: File): string {
		const ext = f.name.split('.').pop()?.toLowerCase() ?? '';
		// Providers routinely hand back text/plain for a .csv, exactly like SAF
		// does on Android; the extension is the more reliable signal.
		return MIME[ext] ?? f.type ?? 'application/octet-stream';
	}

	async function analyze() {
		if (!file || busy) return;
		busy = true;
		error = null;
		result = null;
		outcomes = [];
		events = [];
		try {
			const res = await fetch('/api/analyze', {
				method: 'POST',
				credentials: 'include',
				headers: {
					'content-type': contentType(file),
					// The worker reads the accounts and the payee memory with this
					// token — the one the session already gave us, so the dry-run
					// worker never needs a Turso API token of its own.
					'x-turso-token': auth.session?.jwt ?? ''
				},
				body: await file.arrayBuffer()
			});
			const data = await res.json();
			// A Turso token lives ~7 days; when it lapses the read fails on the
			// worker, not here, so the message is the clue to refresh and retry.
			if (!res.ok && /expired|unauthorized|401/i.test(String(data.error ?? ''))) {
				if (await auth.refresh()) {
					busy = false;
					return analyze();
				}
			}
			if (!res.ok) throw new Error(data.error ?? res.statusText);
			result = data as Analysis;
			// Seed the fallback account the way ImportReviewScreen does: the mode
			// of whatever the document itself attributed.
			const counts = new Map<string, number>();
			for (const t of result.transactions)
				if (t.accountId) counts.set(t.accountId, (counts.get(t.accountId) ?? 0) + 1);
			fallbackAccount = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null;
			await match();
		} catch (e) {
			error = e instanceof Error ? e.message : 'falló el análisis';
		} finally {
			busy = false;
		}
	}

	async function match() {
		if (!result || !auth.session) return;
		matching = true;
		const db = new ReadOnlyDb(auth.session);
		try {
			events = result.transactions.map((c) => candidateToEvent(c, result!.docSha256, fallbackAccount));
			const out = await reconcile(db, events);
			outcomes = out.outcomes;
			factCount = out.facts.length;
		} catch (e) {
			error = e instanceof Error ? e.message : 'falló la conciliación';
		} finally {
			db.close();
			matching = false;
		}
	}

	const ownAccounts = $derived(
		(result?.accounts ?? []).filter((a) => a.type === 'asset' || a.type === 'liability')
	);
	const total = $derived(
		(result?.transactions ?? []).reduce(
			(sum, t) => sum + t.splits.reduce((s, p) => s + p.amountMinor, 0),
			0
		)
	);
</script>

<div class="space-y-4">
	<div
		class="rounded-lg border border-dashed p-6 text-center {dragging
			? 'border-neutral-400 bg-neutral-900'
			: 'border-neutral-700'}"
		role="region"
		ondragover={(e) => {
			e.preventDefault();
			dragging = true;
		}}
		ondragleave={() => (dragging = false)}
		ondrop={(e) => {
			e.preventDefault();
			dragging = false;
			file = e.dataTransfer?.files?.[0] ?? null;
		}}
	>
		<input
			type="file"
			accept=".csv,.pdf,.jpg,.jpeg,.png,.webp,.heic"
			class="mx-auto block text-sm text-neutral-400 file:mr-3 file:rounded file:border-0 file:bg-neutral-800 file:px-3 file:py-1.5 file:text-neutral-200"
			onchange={(e) => (file = e.currentTarget.files?.[0] ?? null)}
		/>
		<p class="mt-3 text-sm text-neutral-500">
			{file ? `${file.name} · ${(file.size / 1024).toFixed(0)} kB · ${contentType(file)}` : 'resumen, extracto CSV o foto de un ticket'}
		</p>
		<button
			class="mt-4 rounded bg-neutral-100 px-4 py-2 font-medium text-neutral-900 disabled:opacity-40"
			disabled={!file || busy}
			onclick={analyze}
		>
			{busy ? 'analizando… (15–25 s)' : 'Analizar'}
		</button>
	</div>

	{#if error}
		<p class="rounded border border-red-900 bg-red-950/40 px-4 py-3 text-sm text-red-300">{error}</p>
	{/if}

	{#if result}
		<div class="flex flex-wrap gap-2 text-xs text-neutral-400">
			<span class="rounded bg-neutral-800 px-2 py-1">{result.kind === 'table' ? 'prompt CSV' : 'prompt documento'}</span>
			<span class="rounded bg-neutral-800 px-2 py-1">{result.model}</span>
			<span class="rounded bg-neutral-800 px-2 py-1">{result.viaGateway ? 'vía AI Gateway' : 'directo a Google'}</span>
			<span class="rounded bg-neutral-800 px-2 py-1">{((result.latencyMs ?? 0) / 1000).toFixed(1)} s</span>
			<span class="rounded bg-neutral-800 px-2 py-1">{result.transactions.length} candidatos</span>
			<span class="rounded bg-neutral-800 px-2 py-1">{money(total)}</span>
			<span class="rounded bg-neutral-800 px-2 py-1">sha {result.docSha256.slice(0, 12)}</span>
		</div>

		<Panel
			title="Contexto · cuentas"
			subtitle="{result.accounts.length} cuentas posteables (padres incluidos)"
			copy={result.accounts.map((a) => `${a.type}\t${a.path}`).join('\n')}
		>
			<table class="w-full text-sm">
				<tbody>
					{#each result.accounts as a (a.id)}
						<tr class="border-b border-neutral-800/60">
							<td class="w-24 py-1 text-neutral-500">{a.type}</td>
							<td class="py-1">{a.path}</td>
							<td class="py-1 text-right font-mono text-xs text-neutral-600">{a.id.slice(0, 8)}</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</Panel>

		<Panel
			title="Contexto · memoria de payees"
			subtitle="{result.history.length} payees que ya categorizaste"
			copy={result.history.map((h) => `${h.payee} → ${h.path} (${h.count})`).join('\n')}
		>
			{#if result.history.length === 0}
				<p class="text-sm text-neutral-500">Sin historial en los últimos 365 días.</p>
			{:else}
				<table class="w-full text-sm">
					<tbody>
						{#each result.history as h (h.payee)}
							<tr class="border-b border-neutral-800/60">
								<td class="py-1">{h.payee}</td>
								<td class="py-1 text-neutral-400">{h.path}</td>
								<td class="w-12 py-1 text-right text-neutral-600">{h.count}</td>
							</tr>
						{/each}
					</tbody>
				</table>
			{/if}
		</Panel>

		<Panel
			title="Prompt"
			subtitle="{result.promptText.length} caracteres"
			copy={result.promptText}
			open
		>
			<Json value={result.promptText} />
		</Panel>

		<Panel title="Partes enviadas al modelo" subtitle="{result.mimeType} · {(result.bytes / 1024).toFixed(0)} kB">
			<Json value={result.request} />
		</Panel>

		<Panel title="responseSchema" copy={JSON.stringify(result.schema, null, 2)}>
			<Json value={result.schema} />
		</Panel>

		<Panel
			title="Respuesta cruda del modelo"
			subtitle={result.usage ? Object.entries(result.usage).map(([k, v]) => `${k}=${v}`).join(' · ') : ''}
			copy={result.rawText ?? ''}
		>
			<Json value={result.rawText ?? ''} />
		</Panel>

		<Panel title="Candidatos normalizados" subtitle="lo que recibe la app" open>
			<div class="overflow-x-auto">
				<table class="w-full text-sm">
					<thead class="text-left text-xs text-neutral-500">
						<tr>
							<th class="py-1 pr-3">fecha</th>
							<th class="py-1 pr-3">dirección</th>
							<th class="py-1 pr-3 text-right">monto</th>
							<th class="py-1 pr-3">payee</th>
							<th class="py-1 pr-3">cuenta</th>
							<th class="py-1">categoría(s)</th>
						</tr>
					</thead>
					<tbody>
						{#each result.transactions as t, i (i)}
							<tr class="border-b border-neutral-800/60 align-top">
								<td class="py-1 pr-3 whitespace-nowrap text-neutral-400">{t.day ?? day(t.date)}</td>
								<td class="py-1 pr-3 text-neutral-400">{t.direction}</td>
								<td class="py-1 pr-3 text-right font-mono whitespace-nowrap"
									>{money(t.splits.reduce((s, p) => s + p.amountMinor, 0), t.commodity)}</td
								>
								<td class="py-1 pr-3">{t.payee}</td>
								<td class="py-1 pr-3 text-neutral-400">{t.accountPath ?? '—'}</td>
								<td class="py-1 text-neutral-400"
									>{t.splits.map((s) => s.categoryPath ?? '—').join(' + ')}</td
								>
							</tr>
						{/each}
					</tbody>
				</table>
			</div>
		</Panel>

		<Panel
			title="Conciliación"
			subtitle={matching
				? 'buscando…'
				: `${factCount} transacciones en la ventana · ${outcomes.filter((o) => o.kind !== 'none').length} con match`}
			open
		>
			<div class="mb-3 flex items-center gap-2 text-sm">
				<label for="fallback" class="text-neutral-400">Cuenta por defecto</label>
				<select
					id="fallback"
					class="rounded border border-neutral-700 bg-neutral-900 px-2 py-1"
					bind:value={fallbackAccount}
					onchange={match}
				>
					<option value={null}>—</option>
					{#each ownAccounts as a (a.id)}
						<option value={a.id}>{a.path}</option>
					{/each}
				</select>
				<span class="text-xs text-neutral-500">solo para filas que el documento no atribuyó</span>
			</div>

			<table class="w-full text-sm">
				<thead class="text-left text-xs text-neutral-500">
					<tr>
						<th class="py-1 pr-3">acción</th>
						<th class="py-1 pr-3">payee</th>
						<th class="py-1 pr-3">eventKey</th>
						<th class="py-1">match</th>
					</tr>
				</thead>
				<tbody>
					{#each outcomes as o, i (i)}
						<tr class="border-b border-neutral-800/60 align-top">
							<td class="py-1 pr-3">
								<span
									class="rounded px-2 py-0.5 text-xs {action(o) === 'crear'
										? 'bg-emerald-900/50 text-emerald-300'
										: action(o) === 'omitir'
											? 'bg-neutral-800 text-neutral-400'
											: 'bg-amber-900/40 text-amber-300'}">{action(o)}</span
								>
							</td>
							<td class="py-1 pr-3">{result.transactions[i]?.payee}</td>
							<td class="py-1 pr-3 font-mono text-xs break-all text-neutral-500">{o.eventKey}</td>
							<td class="py-1 text-neutral-400">
								{#each o.matches as m (m.transactionId)}
									<div>
										{m.relation} · {m.score} · {m.payee} ({dateTime(m.date)})
										<div class="text-xs text-neutral-600">{m.reasons.join(', ')}</div>
									</div>
								{:else}
									—
								{/each}
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</Panel>

		<Panel title="Respuesta del modelo, sin normalizar">
			<Json value={result.rawTransactions} />
		</Panel>
	{/if}
</div>
