<script lang="ts">
	import { auth } from '$lib/auth.svelte';
	import { copyJson } from '$lib/clipboard';
	import { ReadOnlyDb, loadAccountRows, type AccountRow } from '$lib/db';

	type Node = AccountRow & { depth: number; path: string };

	const ORDER: AccountRow['type'][] = ['Asset', 'Liability', 'Income', 'Expense', 'Equity'];
	const LABEL: Record<AccountRow['type'], string> = {
		Asset: 'Activos',
		Liability: 'Pasivos',
		Income: 'Ingresos',
		Expense: 'Gastos',
		Equity: 'Patrimonio'
	};

	let accounts = $state<AccountRow[]>([]);
	let busy = $state(false);
	let error = $state<string | null>(null);
	let copied = $state<string | null>(null);
	let started = false;

	async function load() {
		const session = auth.session;
		if (!session) return;
		busy = true;
		error = null;
		const db = new ReadOnlyDb(session);
		try {
			accounts = await loadAccountRows(db);
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			db.close();
			busy = false;
		}
	}

	/**
	 * The stored rows are a flat parent-pointer list; this walks it into the
	 * tree the app shows, carrying the full "Activos:Banco:Caja" path because
	 * that is the name every other surface (pickers, the import prompt, the
	 * ingest hints) actually uses.
	 */
	function flatten(rows: AccountRow[]): Node[] {
		const children = new Map<string | null, AccountRow[]>();
		for (const row of rows) {
			const list = children.get(row.parentId) ?? [];
			list.push(row);
			children.set(row.parentId, list);
		}
		for (const list of children.values()) list.sort((a, b) => a.name.localeCompare(b.name, 'es'));

		const out: Node[] = [];
		const walk = (parent: string | null, depth: number, prefix: string) => {
			for (const row of children.get(parent) ?? []) {
				const path = prefix ? `${prefix}:${row.name}` : row.name;
				out.push({ ...row, depth, path });
				walk(row.id, depth + 1, path);
			}
		};
		walk(null, 0, '');
		// Orphans: a parent deleted on another device leaves children dangling,
		// and hiding them here would hide a real sync problem.
		const seen = new Set(out.map((n) => n.id));
		for (const row of rows) {
			if (!seen.has(row.id)) out.push({ ...row, depth: 0, path: `⚠ ${row.name}` });
		}
		return out;
	}

	const nodes = $derived(flatten(accounts));
	const byType = $derived(ORDER.map((type) => ({ type, nodes: nodes.filter((n) => n.type === type) })));

	async function copy(key: string, value: unknown) {
		if (await copyJson(value)) {
			copied = key;
			setTimeout(() => (copied = copied === key ? null : copied), 1500);
		}
	}

	const asJson = () =>
		nodes.map((n) => ({
			id: n.id,
			name: n.name,
			path: n.path,
			type: n.type,
			parentId: n.parentId,
			inNetWorth: n.inNetWorth
		}));

	$effect(() => {
		if (auth.signedIn && !started) {
			started = true;
			load();
		}
	});
</script>

<div class="space-y-4">
	<div class="flex items-center gap-2">
		<p class="text-sm text-neutral-500">{accounts.length} cuentas</p>
		<span class="grow"></span>
		<button
			class="rounded border border-neutral-700 px-3 py-1.5 text-sm text-neutral-300 hover:text-neutral-100"
			onclick={() => copy('all', asJson())}
			>{copied === 'all' ? 'copiado' : 'copiar JSON'}</button
		>
		<button
			class="rounded border border-neutral-700 px-3 py-1.5 text-sm disabled:opacity-50"
			disabled={busy}
			onclick={load}>{busy ? '…' : 'recargar'}</button
		>
	</div>

	{#if error}
		<p class="rounded border border-red-900 bg-red-950/40 p-3 text-sm text-red-300">{error}</p>
	{/if}

	{#each byType as group (group.type)}
		{#if group.nodes.length}
			<section class="rounded-lg border border-neutral-800">
				<header class="flex items-center gap-2 border-b border-neutral-800 px-3 py-2">
					<h2 class="text-sm font-medium text-neutral-200">{LABEL[group.type]}</h2>
					<span class="text-xs text-neutral-600">{group.nodes.length}</span>
					<span class="grow"></span>
					<button
						class="text-xs text-neutral-500 hover:text-neutral-200"
						onclick={() =>
							copy(
								group.type,
								asJson().filter((a) => a.type === group.type)
							)}>{copied === group.type ? 'copiado' : 'copiar JSON'}</button
					>
				</header>
				<div class="divide-y divide-neutral-900">
					{#each group.nodes as node (node.id)}
						<div class="flex items-baseline gap-3 px-3 py-1.5 text-sm">
							<span style="padding-left: {node.depth * 16}px" class="min-w-0 grow truncate">
								<span class="text-neutral-200">{node.name}</span>
								{#if !node.inNetWorth}
									<span class="ml-2 text-xs text-neutral-600">fuera del patrimonio</span>
								{/if}
							</span>
							<span class="shrink-0 font-mono text-xs text-neutral-600">{node.id}</span>
						</div>
					{/each}
				</div>
			</section>
		{/if}
	{:else}
		<p class="px-3 py-6 text-center text-sm text-neutral-600">{busy ? 'cargando…' : 'sin cuentas'}</p>
	{/each}
</div>
