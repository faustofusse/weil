<script lang="ts">
	let { value, max = 200_000 }: { value: unknown; max?: number } = $props();
	const text = $derived(
		typeof value === 'string' ? value : JSON.stringify(value, null, 2)
	);
</script>

<pre
	class="max-h-[32rem] overflow-auto rounded bg-neutral-950 p-3 text-xs leading-relaxed whitespace-pre-wrap text-neutral-300">{text.slice(
		0,
		max
	)}{text.length > max ? `\n… (${text.length - max} caracteres más)` : ''}</pre>
