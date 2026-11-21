// Renders the Worker's HTML pages at build time, each from its one source of truth:
//   GET /privacy  <- docs/launch/privacy-policy.md
//   GET /terms    <- docs/launch/terms-of-service.md
//   GET /         a tiny landing page that links to both
// Writes src/generated/pages.ts (not committed). wrangler runs this before `deploy` and `dev`
// (the `build` section of wrangler.jsonc), vitest.config.ts before the tests, and `npm run typecheck`.
// HTML comments in the Markdown (drafting notes) are left out; [PLACEHOLDERS] stay visible, highlighted.

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Marked } from "marked";

const here = dirname(fileURLToPath(import.meta.url));
const PRIVACY_SOURCE = join(here, "../../docs/launch/privacy-policy.md");
const TERMS_SOURCE = join(here, "../../docs/launch/terms-of-service.md");
const OUT = join(here, "../src/generated/pages.ts");

const escapeHtml = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** GitHub's heading anchors, so /privacy#7-your-choices-and-rights matches the repo's rendering (and /terms#…). */
function slug(text) {
  return text
    .toLowerCase()
    .replace(/<[^>]*>/g, "")
    .replace(/&[a-z]+;|&#\d+;/g, "")
    .replace(/[^\p{L}\p{N}\s_-]/gu, "")
    .trim()
    .replace(/\s/g, "-");
}

function renderPolicy(markdown, fallbackTitle) {
  const seen = new Map();
  const marked = new Marked({ gfm: true });
  marked.use({
    renderer: {
      heading({ tokens, depth }) {
        const inner = this.parser.parseInline(tokens);
        let id = slug(inner);
        const n = seen.get(id) ?? 0;
        seen.set(id, n + 1);
        if (n) id = `${id}-${n}`;
        return `<h${depth} id="${id}">${inner}</h${depth}>\n`;
      },
    },
  });
  const source = markdown.replace(/<!--[\s\S]*?-->\s*/g, "");
  const title = (source.match(/^#\s+(.+)$/m)?.[1] ?? fallbackTitle).trim();
  const body = marked
    .parse(source)
    .replace(/<table>/g, '<div class="table"><table>')
    .replace(/<\/table>/g, "</table></div>")
    // Placeholders the developer still has to fill in, such as [LEGAL NAME] (only in text, not in tags).
    .replace(/>([^<]*)</g, (_, text) => `>${text.replace(/\[([A-Z][A-Z0-9 ,.'/-]*)\]/g, "<mark>[$1]</mark>")}<`);
  return { title, body };
}

const STYLE = `
:root{color-scheme:light dark;--fg:#1f2328;--muted:#59636e;--bg:#fff;--line:#d8dee4;--link:#0b57d0;--mark:#fff1a8;--code:#f1f3f5}
@media (prefers-color-scheme:dark){:root{--fg:#e3e6e8;--muted:#9ba5ae;--bg:#121416;--line:#30363d;--link:#8ab4f8;--mark:#594a00;--code:#1e2226}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%;text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--fg);font:17px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}
main{max-width:44rem;margin:0 auto;padding:2rem 1.25rem 4rem}
h1{font-size:1.9rem;line-height:1.25;margin:0 0 1rem}
h2{font-size:1.35rem;line-height:1.3;margin:2.5rem 0 .75rem;padding-top:1.25rem;border-top:1px solid var(--line)}
h3{font-size:1.1rem;margin:1.75rem 0 .5rem}
p,ul,ol{margin:.75rem 0}
ul,ol{padding-left:1.4rem}
li{margin:.3rem 0}
li>ul{margin:.3rem 0}
a{color:var(--link);text-underline-offset:2px;overflow-wrap:anywhere}
code{font:.88em/1.4 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;background:var(--code);padding:.1em .3em;border-radius:4px;overflow-wrap:anywhere}
.table{overflow-x:auto;margin:1rem 0}
table{border-collapse:collapse;width:100%;font-size:.94rem;line-height:1.45}
th,td{text-align:left;vertical-align:top;padding:.55rem .6rem;border-bottom:1px solid var(--line)}
th{font-weight:600;border-bottom-width:2px}
mark{background:var(--mark);color:inherit;padding:0 .15em;border-radius:3px}
.muted{color:var(--muted)}
`.trim();

function page(title, body) {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>${escapeHtml(title)}</title>
<style>${STYLE}</style>
</head>
<body>
<main>
${body.trim()}
</main>
</body>
</html>
`;
}

const policy = renderPolicy(readFileSync(PRIVACY_SOURCE, "utf8"), "Privacy policy");
const privacy = page(policy.title, policy.body);
const tos = renderPolicy(readFileSync(TERMS_SOURCE, "utf8"), "Terms of service");
const terms = page(tos.title, tos.body);
const home = page(
  "Transmit",
  `<h1>Transmit</h1>
<p>Transmit is an Android app for language learners. It shows your Google Messages chats with AI translation and a tutor.</p>
<p class="muted">This address is Transmit's server. It hands out the AI keys for the free week and for subscriptions; it has nothing else to show.</p>
<p><a href="/privacy">Privacy policy</a> · <a href="/terms">Terms of service</a></p>`,
);

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(
  OUT,
  `// Generated by scripts/build-pages.mjs from docs/launch/privacy-policy.md and terms-of-service.md. Don't edit; don't commit.\n\n` +
    `export const PRIVACY_HTML = ${JSON.stringify(privacy)};\n\n` +
    `export const TERMS_HTML = ${JSON.stringify(terms)};\n\n` +
    `export const HOME_HTML = ${JSON.stringify(home)};\n`,
);
console.log(`build-pages: wrote ${OUT.slice(join(here, "..").length + 1)} (privacy ${privacy.length} bytes, terms ${terms.length} bytes)`);
