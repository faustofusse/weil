<script lang="ts">
	import type { Snippet } from 'svelte';

	/** Collapsible section with a title, an optional count and a copy button. */
	let {
		title,
		subtitle = '',
		open = false,
		copy = null,
		children
	}: {
		title: string;
		subtitle?: string;
		open?: boolean;
		copy?: string | null;
		children: Snippet;
	} = $props();

	// `open` is only an initial value; after mount the panel owns its state.
	// svelte-ignore state_referenced_locally
	let expanded = $state(open);
	let copied = $state(false);

	async function copyIt(e: MouseEvent) {
		e.stopPropagation();
		if (copy == null) return;
		await navigator.clipboard.writeText(copy);
		copied = true;
		setTimeout(() => (copied = false), 1200);
	}
</script>

<section class="rounded-lg border border-neutral-800 bg-neutral-900/40">
	<header class="flex items-center gap-3 px-4 py-3">
		<button
			class="flex grow cursor-pointer items-center gap-3 text-left select-none"
			aria-expanded={expanded}
			onclick={() => (expanded = !expanded)}
		>
			<span class="text-neutral-500">{expanded ? '▾' : '▸'}</span>
			<h2 class="font-medium">{title}</h2>
			{#if subtitle}
				<span class="text-sm text-neutral-500">{subtitle}</span>
			{/if}
		</button>
		{#if copy != null}
			<button
				class="rounded border border-neutral-700 px-2 py-1 text-xs text-neutral-400 hover:text-neutral-100"
				onclick={copyIt}
			>
				{copied ? 'copiado' : 'copiar'}
			</button>
		{/if}
	</header>
	{#if expanded}
		<div class="border-t border-neutral-800 px-4 py-3">
			{@render children()}
		</div>
	{/if}
</section>
