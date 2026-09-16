/**
 * Email ingestion: MIME parsing and HTML sanitization.
 *
 * The raw message is parsed with postal-mime (multipart-aware, decodes
 * quoted-printable/base64 bodies and RFC 2047 encoded headers), then the
 * HTML body is sanitized server-side so every client — Android WebView,
 * iOS WKWebView, desktop fallback — receives the same safe markup instead
 * of each platform reimplementing the rules.
 */
import PostalMime from 'postal-mime';

/** Plain-text bodies are a fallback/preview; the HTML one is what gets rendered. */
export const BODY_LIMIT = 10_000;
export const HTML_LIMIT = 200_000;

/** Dropped wholesale: active content or external resource loaders. */
const DROPPED_TAGS = ['script', 'iframe', 'object', 'embed', 'link', 'meta', 'base', 'form', 'noscript'];

export interface ParsedEmail {
  /** RFC 2047-decoded, ready to display. */
  subject: string | null;
  text: string | null;
  html: string | null;
  /** The message's own Date header, epoch ms, when present and parseable. */
  sentAt: number | null;
}

/**
 * Parses a raw RFC 822 message. Both bodies are truncated; `html` is
 * sanitized (see [sanitizeHtml]).
 */
export async function parseEmail(raw: ArrayBuffer): Promise<ParsedEmail> {
  const parsed = await PostalMime.parse(raw);
  const html = parsed.html ? await sanitizeHtml(parsed.html.slice(0, HTML_LIMIT)) : null;
  const sent = parsed.date ? Date.parse(parsed.date) : NaN;
  return {
    subject: parsed.subject?.trim() || null,
    text: parsed.text ? parsed.text.slice(0, BODY_LIMIT) : null,
    html,
    sentAt: Number.isNaN(sent) ? null : sent,
  };
}

/**
 * Strips active content and defuses remote images, using the runtime's own
 * streaming HTML parser (no regex guesswork, no Node-only dependency).
 *
 * Images keep their URL in `data-src` instead of `src`, so the client can
 * show them only when the user asks — an unrequested load is a read receipt
 * for the sender. `<style>` blocks are preserved because table-based emails
 * are unreadable without them; a `url()` inside one can still reference a
 * remote asset, so clients should also block network loads at the webview
 * level until the user opts in.
 */
export async function sanitizeHtml(html: string): Promise<string> {
  let rewriter = new HTMLRewriter();

  for (const tag of DROPPED_TAGS) {
    rewriter = rewriter.on(tag, {
      element(el) {
        el.remove();
      },
    });
  }

  rewriter = rewriter.on('img', {
    element(el) {
      const src = el.getAttribute('src');
      el.removeAttribute('src');
      el.removeAttribute('srcset');
      if (src && !src.startsWith('cid:')) el.setAttribute('data-src', src);
    },
  });

  rewriter = rewriter.on('*', {
    element(el) {
      // The iterator is invalidated by any removeAttribute, so the pairs are
      // materialized before anything is touched.
      const attributes = [...el.attributes];
      for (const pair of attributes) {
        const name = pair[0];
        const value = pair[1] ?? '';
        if (!name) continue;
        const attr = name.toLowerCase();
        // Inline event handlers (onclick, onerror, onload, ...).
        if (attr.startsWith('on')) {
          el.removeAttribute(name);
          continue;
        }
        if ((attr === 'href' || attr === 'src' || attr === 'action') && isUnsafeUrl(value)) {
          el.removeAttribute(name);
        }
      }
    },
  });

  const response = rewriter.transform(new Response(html, { headers: { 'content-type': 'text/html' } }));
  return response.text();
}

/** `javascript:`/`vbscript:`/`data:` URLs, tolerating whitespace and control chars. */
function isUnsafeUrl(value: string): boolean {
  const normalized = value.replace(/[\s\u0000-\u001f]/g, '').toLowerCase();
  return (
    normalized.startsWith('javascript:') ||
    normalized.startsWith('vbscript:') ||
    // data: is fine for inline images, dangerous for markup.
    (normalized.startsWith('data:') && !normalized.startsWith('data:image/'))
  );
}
