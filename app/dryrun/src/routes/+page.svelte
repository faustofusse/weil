<script lang="ts">
	import { auth } from '$lib/auth.svelte';
	import {
		ReadOnlyDb,
		browseEmails,
		browseNotifications,
		emailFacets,
		loadAppLabels,
		loadBody,
		loadSimilarPool,
		notificationFacets,
		type Facet,
		type Message,
		type MessageBody,
		type SimilarCandidate
	} from '$lib/db';
	import { copyJson } from '$lib/clipboard';
	import { dateTime } from '$lib/format';
	import { rankSimilar, signature, type Scored } from '$lib/similar';

	type Tab = 'notification' | 'email';

	let tab = $state<Tab>('notification');
	let days = $state(30);
	let search = $state('');
	let pageSize = $state(50);
	let origins = $state<string[]>([]);
	let rows = $state<Message[]>([]);
	let more = $state(false);
	let facets = $state<Facet[]>([]);
	let open = $state<string | null>(null);
	let facetMenu = $state<HTMLDetailsElement | null>(null);
	let similar = $state<Record<string, Array<Scored<SimilarCandidate>>>>({});
	let searchingSimilar = $state<string | null>(null);
	let copied = $state<string | null>(null);

	// The pool is per app and doesn't change while the page is open, so the
	// second "similar" click on the same bank is free.
	const pools = new Map<string, SimilarCandidate[]>();
	let bodies = $state<Record<string, MessageBody>>({});
	let busy = $state(false);
	let paging = $state(false);
	let error = $state<string | null>(null);
	let started = false;

	// One client for the whole session: the page reloads on every filter
	// change, and building/tearing down a libsql client each time was paying
	// for a new connection per keystroke-worth of work.
	let client: ReadOnlyDb | null = null;
	function db(): ReadOnlyDb {
		const session = auth.session;
		if (!session) throw new Error('sin sesión');
		client ??= new ReadOnlyDb(session);
		return client;
	}

	// Labels and facets change far more slowly than the list; caching them by
	// window keeps the `group by` off the path of every search and every page.
	let labels: Map<string, string> | null = null;
	const facetCache = new Map<string, Facet[]>();

	function query(before?: number) {
		return { since: Date.now() - days * 86_400_000, search, origins, limit: pageSize, before };
	}

	async function fetchPage(before?: number) {
		const conn = db();
		labels ??= await loadAppLabels(conn);
		return tab === 'notification'
			? browseNotifications(conn, query(before), labels)
			: browseEmails(conn, query(before));
	}

	async function loadFacets() {
		const since = Date.now() - days * 86_400_000;
		const key = `${tab}:${days}`;
		const hit = facetCache.get(key);
		if (hit) {
			facets = hit;
			return;
		}
		const conn = db();
		labels ??= await loadAppLabels(conn);
		const next =
			tab === 'notification'
				? await notificationFacets(conn, since, labels)
				: await emailFacets(conn, since);
		facetCache.set(key, next);
		facets = next;
	}

	async function load() {
		if (!auth.session) return;
		busy = true;
		error = null;
		try {
			const page = await fetchPage();
			rows = page.rows;
			more = page.more;
			bodies = {};
			// Deliberately not awaited: the list is what the user is waiting
			// for, the chips can land a moment later.
			loadFacets().catch(() => {});
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
			rows = [];
			more = false;
		} finally {
			busy = false;
		}
	}

	async function loadMore() {
		const last = rows.at(-1);
		if (!last || paging) return;
		paging = true;
		try {
			const page = await fetchPage(last.date);
			rows = [...rows, ...page.rows];
			more = page.more;
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			paging = false;
		}
	}

	async function toggle(row: Message) {
		if (open === row.id) {
			open = null;
			return;
		}
		open = row.id;
		if (bodies[row.id]) return;
		const body = await loadBody(db(), row.kind, row.id);
		bodies = { ...bodies, [row.id]: body };
	}

	/** The shape worth pasting into a model: one row, everything about it. */
	function asJson(row: Message) {
		return {
			id: row.id,
			kind: row.kind,
			package: row.origin,
			app: row.originLabel,
			category: row.category,
			postedAt: new Date(row.date).toISOString(),
			title: row.title,
			text: bodies[row.id]?.text ?? row.snippet,
			html: bodies[row.id]?.html ?? null,
			signature: signature(`${row.title} ${bodies[row.id]?.text ?? row.snippet}`)
		};
	}

	async function copy(key: string, value: unknown) {
		if (await copyJson(value)) {
			copied = key;
			setTimeout(() => (copied = copied === key ? null : copied), 1500);
		}
	}

	/**
	 * Similar = same app, same template. Title and body are scored together
	 * because several banks put the verb in the title and the amount in the
	 * body, and either half alone is ambiguous.
	 */
	async function findSimilar(row: Message) {
		searchingSimilar = row.id;
		try {
			let pool = pools.get(row.origin);
			if (!pool) {
				pool = await loadSimilarPool(db(), row.origin);
				pools.set(row.origin, pool);
			}
			const text = bodies[row.id]?.text ?? row.snippet;
			const ranked = rankSimilar(
				{ id: row.id, text: `${row.title} ${text}` },
				pool.map((c) => ({ ...c, text: `${c.title} ${c.text}` }))
			);
			// Put the original body back for display/copying; the concatenation
			// above only existed for scoring.
			const byId = new Map(pool.map((c) => [c.id, c]));
			similar = {
				...similar,
				[row.id]: ranked.map((s) => ({ ...s, item: byId.get(s.item.id) ?? s.item }))
			};
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			searchingSimilar = null;
		}
	}

	function groupJson(row: Message) {
		const group = similar[row.id] ?? [];
		return {
			app: row.originLabel,
			package: row.origin,
			template: asJson(row).signature,
			target: asJson(row),
			similar: group.map((s) => ({
				id: s.item.id,
				postedAt: new Date(s.item.date).toISOString(),
				title: s.item.title,
				text: s.item.text,
				score: Number(s.score.toFixed(3)),
				sameTemplate: s.sameTemplate
			}))
		};
	}

	function switchTab(next: Tab) {
		if (tab === next) return;
		tab = next;
		origins = [];
		open = null;
		facets = [];
		load();
	}

	function toggleOrigin(origin: string) {
		origins = origins.includes(origin) ? origins.filter((o) => o !== origin) : [...origins, origin];
		load();
	}

	function clearOrigins() {
		origins = [];
		load();
	}

	// A <details> menu otherwise stays open until the summary is clicked again;
	// closing it on any outside click is what makes it read as a dropdown
	// instead of an accordion.
	function onWindowClick(event: MouseEvent) {
		if (facetMenu?.open && !facetMenu.contains(event.target as Node)) facetMenu.open = false;
	}

	// Search runs on Enter, not per keystroke: every query is a round trip to
	// Turso over a table with tens of thousands of rows.
	function onSearchKey(event: KeyboardEvent) {
		if (event.key === 'Enter') load();
	}

	$effect(() => {
		if (auth.signedIn && !started) {
			started = true;
			load();
		}
	});
</script>

<svelte:window onclick={onWindowClick} />

<div class="space-y-4">
	<div class="flex flex-wrap items-center gap-2">
		<div class="flex rounded border border-neutral-800 p-0.5">
			<button
				class="rounded px-3 py-1 text-sm {tab === 'notification'
					? 'bg-neutral-800 text-neutral-100'
					: 'text-neutral-400'}"
				onclick={() => switchTab('notification')}>Notificaciones</button
			>
			<button
				class="rounded px-3 py-1 text-sm {tab === 'email'
					? 'bg-neutral-800 text-neutral-100'
					: 'text-neutral-400'}"
				onclick={() => switchTab('email')}>Mails</button
			>
		</div>

		<input
			class="min-w-56 grow rounded border border-neutral-800 bg-neutral-900 px-3 py-1.5 text-sm"
			placeholder="buscar en título, cuerpo u origen — Enter"
			bind:value={search}
			onkeydown={onSearchKey}
		/>

		<label class="flex items-center gap-1 text-sm text-neutral-400">
			días
			<input
				type="number"
				min="1"
				class="w-16 rounded border border-neutral-800 bg-neutral-900 px-2 py-1"
				bind:value={days}
				onchange={load}
			/>
		</label>
		<label class="flex items-center gap-1 text-sm text-neutral-400">
			página
			<input
				type="number"
				min="10"
				step="25"
				class="w-20 rounded border border-neutral-800 bg-neutral-900 px-2 py-1"
				bind:value={pageSize}
				onchange={load}
			/>
		</label>
		<button
			class="rounded border border-neutral-700 px-3 py-1.5 text-sm disabled:opacity-50"
			disabled={busy}
			onclick={load}>{busy ? '…' : 'recargar'}</button
		>
	</div>

	{#if error}
		<p class="rounded border border-red-900 bg-red-950/40 p-3 text-sm text-red-300">{error}</p>
	{/if}

	{#if facets.length}
		<details bind:this={facetMenu} class="relative inline-block">
			<summary
				class="cursor-pointer list-none rounded border border-neutral-800 px-3 py-1.5 text-sm text-neutral-300 marker:content-none hover:text-neutral-100"
			>
				{origins.length === 0
					? 'todos los orígenes'
					: `${origins.length} origen${origins.length > 1 ? 'es' : ''}`}
				<span class="text-neutral-600">▾</span>
			</summary>
			<div
				class="absolute z-10 mt-1 max-h-80 w-80 overflow-auto rounded border border-neutral-800 bg-neutral-900 p-1 shadow-xl"
			>
				{#if origins.length}
					<button
						class="w-full rounded px-2 py-1 text-left text-xs text-neutral-500 hover:bg-neutral-800"
						onclick={clearOrigins}>limpiar selección</button
					>
				{/if}
				{#each facets as facet (facet.origin)}
					<label
						class="flex cursor-pointer items-center gap-2 rounded px-2 py-1 text-sm hover:bg-neutral-800"
					>
						<input
							type="checkbox"
							checked={origins.includes(facet.origin)}
							onchange={() => toggleOrigin(facet.origin)}
						/>
						<span class="min-w-0 flex-1 truncate" title={facet.origin}>{facet.label}</span>
						<span class="shrink-0 text-xs text-neutral-500">{facet.count}</span>
					</label>
				{/each}
			</div>
		</details>
	{/if}

	<p class="text-sm text-neutral-500">
		{rows.length}
		{tab === 'notification' ? 'notificaciones' : 'mails'} en {days} días{more ? ' (hay más)' : ''}
	</p>

	<div class="divide-y divide-neutral-900 rounded-lg border border-neutral-800">
		{#each rows as row (row.id)}
			<article>
				<button
					class="flex w-full items-baseline gap-3 px-3 py-2 text-left hover:bg-neutral-900/60"
					onclick={() => toggle(row)}
				>
					<span class="w-36 shrink-0 truncate text-xs text-neutral-500" title={row.origin}
						>{row.originLabel}</span
					>
					<span class="shrink-0 text-xs tabular-nums text-neutral-600">{dateTime(row.date)}</span>
					<span class="min-w-0 grow truncate text-sm">
						<span class="text-neutral-200">{row.title}</span>
						<span class="text-neutral-500"> · {row.snippet.replaceAll('\n', ' ')}</span>
					</span>
				</button>

				{#if open === row.id}
					<div class="space-y-3 border-t border-neutral-900 bg-neutral-950/60 px-3 py-3 text-sm">
						<dl class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs text-neutral-500">
							<dt>id</dt>
							<dd class="font-mono text-neutral-400">{row.id}</dd>
							<dt>origen</dt>
							<dd class="font-mono text-neutral-400">{row.origin}</dd>
							{#if row.category}
								<dt>categoría</dt>
								<dd class="font-mono text-neutral-400">{row.category}</dd>
							{/if}
						</dl>

						<div class="flex flex-wrap gap-2">
							<button
								class="rounded border border-neutral-700 px-2.5 py-1 text-xs text-neutral-300 hover:text-neutral-100"
								onclick={() => copy(`one:${row.id}`, asJson(row))}
								>{copied === `one:${row.id}` ? 'copiado' : 'copiar JSON'}</button
							>
							{#if row.kind === 'notification'}
								<button
									class="rounded border border-neutral-700 px-2.5 py-1 text-xs text-neutral-300 hover:text-neutral-100 disabled:opacity-50"
									disabled={searchingSimilar === row.id}
									onclick={() => findSimilar(row)}
									>{searchingSimilar === row.id ? 'buscando…' : 'buscar similares'}</button
								>
							{/if}
							{#if similar[row.id]}
								<button
									class="rounded border border-neutral-700 px-2.5 py-1 text-xs text-neutral-300 hover:text-neutral-100"
									onclick={() => copy(`group:${row.id}`, groupJson(row))}
									>{copied === `group:${row.id}`
										? 'copiado'
										: `copiar grupo (${(similar[row.id] ?? []).length + 1}) como JSON`}</button
								>
							{/if}
						</div>

						{#if similar[row.id]}
							{@const group = similar[row.id] ?? []}
							{@const exact = group.filter((s) => s.sameTemplate).length}
							<div class="rounded border border-neutral-800">
								<p class="border-b border-neutral-800 px-2 py-1.5 text-xs text-neutral-500">
									{group.length} similares · {exact} con la misma plantilla exacta
								</p>
								<div class="max-h-72 divide-y divide-neutral-900 overflow-auto">
									{#each group as hit (hit.item.id)}
										<div class="flex items-baseline gap-2 px-2 py-1.5 text-xs">
											<span
												class="w-10 shrink-0 tabular-nums {hit.sameTemplate
													? 'text-emerald-400'
													: 'text-neutral-600'}">{Math.round(hit.score * 100)}%</span
											>
											<span class="shrink-0 text-neutral-600">{dateTime(hit.item.date)}</span>
											<span class="min-w-0 grow truncate">
												<span class="text-neutral-300">{hit.item.title}</span>
												<span class="text-neutral-500"
													>· {hit.item.text.replaceAll('\n', ' ')}</span
												>
											</span>
										</div>
									{:else}
										<p class="px-2 py-3 text-center text-xs text-neutral-600">
											ninguna parecida en esta app
										</p>
									{/each}
								</div>
							</div>
						{/if}
						{#if bodies[row.id]}
							<div>
								<p class="mb-1 text-xs text-neutral-500">
									{row.kind === 'email' ? 'body_text' : 'text'} · {row.textLength} car.
								</p>
								<pre
									class="max-h-96 overflow-auto rounded bg-neutral-900 p-2 text-xs whitespace-pre-wrap text-neutral-300">{bodies[
										row.id
									]?.text || '(vacío)'}</pre>
							</div>
							{#if row.kind === 'email'}
								<div>
									<p class="mb-1 text-xs text-neutral-500">body_html · {row.htmlLength} car.</p>
									{#if bodies[row.id]?.html}
										<pre
											class="max-h-96 overflow-auto rounded bg-neutral-900 p-2 text-xs whitespace-pre-wrap text-neutral-300">{bodies[
												row.id
											]?.html}</pre>
									{:else}
										<p class="text-xs text-neutral-600">(vacío)</p>
									{/if}
								</div>
							{/if}
						{:else}
							<p class="text-xs text-neutral-600">cargando cuerpo…</p>
						{/if}
					</div>
				{/if}
			</article>
		{:else}
			<p class="px-3 py-6 text-center text-sm text-neutral-600">
				{busy ? 'cargando…' : 'nada en esta ventana'}
			</p>
		{/each}

		{#if more}
			<button
				class="w-full px-3 py-2.5 text-sm text-neutral-400 hover:bg-neutral-900/60 disabled:opacity-50"
				disabled={paging}
				onclick={loadMore}>{paging ? 'cargando…' : 'cargar más'}</button
			>
		{/if}
	</div>
</div>
