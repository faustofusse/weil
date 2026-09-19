<script lang="ts">
	import Json from '$lib/components/Json.svelte';
	import Panel from '$lib/components/Panel.svelte';
	import { auth } from '$lib/auth.svelte';
	import {
		ReadOnlyDb,
		knownSourceRefs,
		loadAccountRows,
		loadEmails,
		loadNotifications,
		type AccountRow,
		type EmailRow,
		type NotificationRow
	} from '$lib/db';
	import {
		buildInbox,
		movementToEvent,
		parseEmail,
		parseNotification,
		type CandidateEvent,
		type InboxCandidate,
		type IngestedMovement,
		type Outcome,
		type ScannedRow
	} from '$lib/kotlin';
	import { action, reconcile } from '$lib/reconcile';
	import { dateTime, money } from '$lib/format';

	let days = $state(30);
	let busy = $state(false);
	let error = $state<string | null>(null);
	let accounts = $state<AccountRow[]>([]);
	let notifications = $state<NotificationRow[]>([]);
	let emails = $state<EmailRow[]>([]);
	let scanned = $state<ScannedRow[]>([]);
	let inbox = $state<InboxCandidate[]>([]);
	let outcomes = $state<Outcome[]>([]);
	let events = $state<CandidateEvent[]>([]);
	let factCount = $state(0);
	let showIgnored = $state(false);

	/** The same window and the same order `IngestRepository.inbox` uses. */
	async function scan() {
		if (!auth.session || busy) return;
		busy = true;
		error = null;
		const db = new ReadOnlyDb(auth.session);
		try {
			const since = Date.now() - days * 86_400_000;
			[accounts, notifications, emails] = await Promise.all([
				loadAccountRows(db),
				loadNotifications(db, since),
				loadEmails(db, since)
			]);
			// Only refs of recognized messages matter, but asking for all of them
			// in one go is one query instead of two passes.
			const refs = [...notifications.map((n) => n.id), ...emails.map((e) => e.id)];
			const known = await knownSourceRefs(db, refs);
			const result = buildInbox(notifications, emails, accounts, known);
			scanned = result.scanned;
			inbox = result.inbox;

			// The movement the rules produced, not a reconstruction of it: the
			// event's payee and sign come from the parser, the account from the
			// hint resolution buildInbox already did.
			const movements = new Map(
				result.scanned.flatMap((s) => (s.movement ? [[s.ref, s.movement] as const] : []))
			);
			events = inbox.flatMap((c) => {
				const movement = movements.get(c.ref);
				return movement ? [movementToEvent(movement, c.candidate.accountId ?? null)] : [];
			});
			const out = await reconcile(db, events);
			outcomes = out.outcomes;
			factCount = out.facts.length;
		} catch (e) {
			error = e instanceof Error ? e.message : 'falló el escaneo';
		} finally {
			db.close();
			busy = false;
		}
	}

	// ---- manual rule testing ----
	let rawPackage = $state('com.mercadopago.wallet');
	let rawTitle = $state('Pagaste a Spotify');
	let rawBody = $state('Debitamos $ 5.895,57 de tu cuenta.');
	let rawFrom = $state('');
	let manual = $state<IngestedMovement | null>(null);
	let manualRan = $state(false);

	function runManual() {
		manualRan = true;
		manual = rawFrom.trim()
			? parseEmail({
					id: 'manual',
					fromEmail: rawFrom.trim(),
					subject: rawTitle,
					bodyText: rawBody,
					bodyHtml: null,
					receivedAt: Date.now()
				})
			: parseNotification({
					id: 'manual',
					packageName: rawPackage.trim(),
					title: rawTitle,
					text: rawBody,
					postTime: Date.now()
				});
	}

	const recognized = $derived(scanned.filter((s) => s.movement));
	const ignored = $derived(scanned.filter((s) => !s.movement));
</script>

<div class="space-y-4">
	<div class="flex flex-wrap items-center gap-3 rounded-lg border border-neutral-800 bg-neutral-900/40 p-4">
		<label for="days" class="text-sm text-neutral-400">Ventana</label>
		<input
			id="days"
			type="number"
			min="1"
			max="365"
			class="w-20 rounded border border-neutral-700 bg-neutral-900 px-2 py-1 text-sm"
			bind:value={days}
		/>
		<span class="text-sm text-neutral-500">días</span>
		<button
			class="rounded bg-neutral-100 px-4 py-2 text-sm font-medium text-neutral-900 disabled:opacity-40"
			disabled={busy}
			onclick={scan}>{busy ? 'escaneando…' : 'Escanear'}</button
		>
		{#if scanned.length > 0}
			<span class="text-sm text-neutral-500">
				{notifications.length} notificaciones · {emails.length} mails · {recognized.length} reconocidos
				· {inbox.length} ofrecidos · {factCount} transacciones en la ventana
			</span>
		{/if}
	</div>

	{#if error}
		<p class="rounded border border-red-900 bg-red-950/40 px-4 py-3 text-sm text-red-300">{error}</p>
	{/if}

	<Panel title="Probar las reglas a mano" subtitle="una notificación o un mail escritos acá">
		<div class="space-y-2 text-sm">
			<div class="flex gap-2">
				<input
					class="w-1/2 rounded border border-neutral-700 bg-neutral-900 px-2 py-1"
					placeholder="package (notificación)"
					bind:value={rawPackage}
				/>
				<input
					class="w-1/2 rounded border border-neutral-700 bg-neutral-900 px-2 py-1"
					placeholder="from (mail; si lo llenás se usan las reglas de mail)"
					bind:value={rawFrom}
				/>
			</div>
			<input
				class="w-full rounded border border-neutral-700 bg-neutral-900 px-2 py-1"
				placeholder="título / asunto"
				bind:value={rawTitle}
			/>
			<textarea
				class="h-24 w-full rounded border border-neutral-700 bg-neutral-900 px-2 py-1 font-mono text-xs"
				placeholder="cuerpo"
				bind:value={rawBody}
			></textarea>
			<button
				class="rounded border border-neutral-700 px-3 py-1.5 text-neutral-300 hover:text-neutral-100"
				onclick={runManual}>Aplicar reglas</button
			>
			{#if manualRan}
				{#if manual}
					<Json value={manual} />
				{:else}
					<p class="text-neutral-500">Ninguna regla reconoce este mensaje (no es un movimiento).</p>
				{/if}
			{/if}
		</div>
	</Panel>

	{#if inbox.length > 0}
		<Panel title="Movimientos detectados" subtitle="{inbox.length} filas" open>
			<table class="w-full text-sm">
				<thead class="text-left text-xs text-neutral-500">
					<tr>
						<th class="py-1 pr-3">acción</th>
						<th class="py-1 pr-3">regla</th>
						<th class="py-1 pr-3">fecha</th>
						<th class="py-1 pr-3 text-right">monto</th>
						<th class="py-1 pr-3">payee</th>
						<th class="py-1 pr-3">cuenta</th>
						<th class="py-1">match</th>
					</tr>
				</thead>
				<tbody>
					{#each inbox as c, i (c.ref)}
						<tr class="border-b border-neutral-800/60 align-top">
							<td class="py-1 pr-3">
								{#if outcomes[i]}
									<span
										class="rounded px-2 py-0.5 text-xs {action(outcomes[i]) === 'crear'
											? 'bg-emerald-900/50 text-emerald-300'
											: action(outcomes[i]) === 'omitir'
												? 'bg-neutral-800 text-neutral-400'
												: 'bg-amber-900/40 text-amber-300'}">{action(outcomes[i])}</span
									>
								{/if}
							</td>
							<td class="py-1 pr-3 font-mono text-xs text-neutral-500">{c.ruleId}</td>
							<td class="py-1 pr-3 whitespace-nowrap text-neutral-400">{dateTime(c.candidate.date)}</td>
							<td class="py-1 pr-3 text-right font-mono whitespace-nowrap"
								>{money(c.candidate.splits[0]?.amountMinor ?? 0, c.candidate.commodity)}</td
							>
							<td class="py-1 pr-3">{c.candidate.payee}</td>
							<td class="py-1 pr-3 text-neutral-400">{c.candidate.accountPath ?? '—'}</td>
							<td class="py-1 text-neutral-400">
								{#each outcomes[i]?.matches ?? [] as m (m.transactionId)}
									<div>
										{m.relation} · {m.score} · {m.payee}
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

		<Panel title="Eventos que ve el matcher" subtitle="CandidateEvent + eventKey">
			<Json value={events} />
		</Panel>
	{/if}

	{#if scanned.length > 0}
		<Panel title="Mensajes reconocidos, crudos" subtitle="{recognized.length} de {scanned.length}">
			<Json value={recognized} />
		</Panel>

		<Panel title="Mensajes ignorados" subtitle="{ignored.length} · el allowlist no los reconoce">
			<label class="mb-2 flex items-center gap-2 text-sm text-neutral-400">
				<input type="checkbox" bind:checked={showIgnored} /> mostrar el cuerpo que leyeron las reglas
			</label>
			<table class="w-full text-sm">
				<tbody>
					{#each ignored as s (s.ref)}
						<tr class="border-b border-neutral-800/60 align-top">
							<td class="w-24 py-1 pr-3 text-xs text-neutral-500">{s.source}</td>
							<td class="py-1 pr-3">{s.title}</td>
							{#if showIgnored}
								<td class="py-1 font-mono text-xs text-neutral-600">{s.body.slice(0, 300)}</td>
							{/if}
						</tr>
					{/each}
				</tbody>
			</table>
		</Panel>
	{/if}
</div>
