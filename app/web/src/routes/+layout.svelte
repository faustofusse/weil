<script lang="ts">
	import '../app.css';
	import { page } from '$app/state';
	import { auth } from '$lib/auth.svelte';
	import { onMount } from 'svelte';

	let { children } = $props();

	onMount(() => auth.boot());

	const tabs = [
		{ href: '/', label: 'Mensajes' },
		{ href: '/accounts', label: 'Cuentas' },
		{ href: '/inbox', label: 'Detección' },
		{ href: '/import', label: 'Importar' }
	];
</script>

<div class="mx-auto min-h-screen max-w-5xl px-5 py-6">
	<header class="mb-6 flex items-center gap-4">
		<div>
			<h1 class="text-lg font-semibold">weil</h1>
			<p class="text-sm text-neutral-500">tu base, en crudo y de solo lectura</p>
		</div>
		<span class="grow"></span>
		{#if auth.signedIn}
			<nav class="flex gap-1">
				{#each tabs as tab (tab.href)}
					<a
						href={tab.href}
						class="rounded px-3 py-1.5 text-sm {page.url.pathname === tab.href
							? 'bg-neutral-800 text-neutral-100'
							: 'text-neutral-400 hover:text-neutral-100'}">{tab.label}</a
					>
				{/each}
			</nav>
			<button
				class="rounded border border-neutral-700 px-3 py-1.5 text-sm text-neutral-400 hover:text-neutral-100"
				onclick={() => auth.signOut()}>salir</button
			>
		{/if}
	</header>

	{#if auth.phase === 'booting'}
		<p class="text-neutral-500">{auth.status || 'cargando…'}</p>
	{:else if !auth.signedIn}
		<div class="rounded-lg border border-neutral-800 bg-neutral-900/40 p-8 text-center">
			<p class="mb-1 text-neutral-300">Entrá con tu passkey de finance</p>
			<p class="mb-5 text-sm text-neutral-500">
				Misma cuenta que la app: las cuentas, el historial de payees y el ledger salen de tu base.
			</p>
			<button
				class="rounded bg-neutral-100 px-4 py-2 font-medium text-neutral-900 disabled:opacity-50"
				disabled={auth.phase === 'signing-in'}
				onclick={() => auth.signIn()}
			>
				{auth.phase === 'signing-in' ? auth.status || 'esperando…' : 'Entrar'}
			</button>
			{#if auth.error}
				<p class="mt-4 text-sm text-red-400">{auth.error}</p>
			{/if}
		</div>
	{:else}
		{@render children()}
	{/if}
</div>
