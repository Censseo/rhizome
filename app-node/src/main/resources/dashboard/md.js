/*
 * Markdown renderer for the node's self-served documentation. Covers the subset the
 * repository docs actually use: ATX headings, paragraphs, fenced code, `- ` and `1. `
 * lists, blockquotes, pipe tables, thematic breaks, and inline code / bold / italic /
 * links.
 *
 * Everything is built with createElement + textContent — never innerHTML. The markdown
 * ships inside the jar so it is trusted content, but the dashboard holds wallet keys in
 * memory, and a renderer that cannot inject markup cannot become the hole that leaks
 * them (the same reason the SPA's CSP has no 'unsafe-inline' for scripts).
 *
 * Links are resolved by the caller: repository markdown points at sibling specs with
 * relative paths (../boxes/spec.md) and at source files the node does not serve
 * (../../lib-core/.../ChainEngine.java#L12-L20). The resolver maps the former to SPA
 * routes and rejects the latter, which render as inert path references rather than as
 * links that 404.
 */
'use strict';

const RzMd = (() => {

  const FENCE = /^```\s*([\w+-]*)\s*$/;
  const HEADING = /^(#{1,6})\s+(.*?)\s*#*\s*$/;
  const HR = /^ {0,3}(?:-{3,}|\*{3,}|_{3,})\s*$/;
  const QUOTE = /^ {0,3}>\s?(.*)$/;
  const UL = /^ {0,3}[-*+]\s+(.*)$/;
  const OL = /^ {0,3}(\d+)[.)]\s+(.*)$/;
  const TABLE_SEP = /^\s*\|?(?:\s*:?-{2,}:?\s*\|)+\s*:?-{2,}:?\s*\|?\s*$/;

  /* ---------------- inline ---------------- */

  // Ordered alternation: code spans win over emphasis so `**` inside backticks stays literal.
  const INLINE = new RegExp([
    /(`+)([\s\S]*?)\1/.source,                        // 1-2 code span
    /\[([^\]]+)\]\(([^)\s]+)\)/.source,               // 3-4 link
    /\*\*([\s\S]+?)\*\*/.source,                      // 5   bold
    /(?<![\w*])\*([^*\n]+)\*(?!\w)/.source,           // 6   italic *
    /(?<![\w_])_([^_\n]+)_(?!\w)/.source,             // 7   italic _
  ].join('|'), 'g');

  /** Renders inline markup into `parent`. */
  function inline(parent, text, ctx) {
    // Every match is collected before any node is built, because this function recurses (the
    // body of a bold span or a link is itself inline markup) and INLINE is one compiled regex
    // carrying `g` state: letting a nested call move lastIndex would make this scan resume at
    // the inner one's position and re-emit text forever.
    const found = [];
    INLINE.lastIndex = 0;
    for (let m; (m = INLINE.exec(text)) !== null;) found.push(m);

    let last = 0;
    for (const m of found) {
      if (m.index > last) parent.append(document.createTextNode(text.slice(last, m.index)));
      if (m[2] !== undefined) {
        parent.append(tag('code', null, m[2].trim()));
      } else if (m[3] !== undefined) {
        parent.append(link(m[3], m[4], ctx));
      } else if (m[5] !== undefined) {
        inline(parent.appendChild(tag('strong')), m[5], ctx);
      } else {
        inline(parent.appendChild(tag('em')), m[6] !== undefined ? m[6] : m[7], ctx);
      }
      last = m.index + m[0].length;
    }
    if (last < text.length) parent.append(document.createTextNode(text.slice(last)));
    return parent;
  }

  function link(label, href, ctx) {
    const target = ctx.resolveLink ? ctx.resolveLink(href) : href;
    if (!target) {
      // A reference the node cannot serve: keep it visible and copyable, but not clickable.
      const ref = tag('code', { class: 'md-ref', title: href });
      return inline(ref, label, ctx);
    }
    const a = tag('a', { href: target });
    if (/^https?:/.test(target)) { a.setAttribute('rel', 'noopener noreferrer'); a.setAttribute('target', '_blank'); }
    return inline(a, label, ctx);
  }

  function tag(name, attrs, text) {
    const node = document.createElement(name);
    if (attrs) for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  /* ---------------- headings / anchors ---------------- */

  function slugify(text, seen) {
    const base = text.toLowerCase()
      .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
      .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'section';
    const n = (seen.get(base) || 0) + 1;
    seen.set(base, n);
    return n === 1 ? base : base + '-' + n;
  }

  /* ---------------- blocks ---------------- */

  /**
   * markdown -> { node: DocumentFragment, headings: [{ level, text, id }] }.
   * `opts.resolveLink(href)` returns a usable href, or a falsy value to render the link inert.
   * `opts.anchorHref(id)` builds the href a table-of-contents entry points at.
   */
  function render(markdown, opts) {
    const ctx = Object.assign({ seen: new Map(), headings: [] }, opts || {});
    const frag = document.createDocumentFragment();
    const lines = String(markdown).replace(/\r\n?/g, '\n').split('\n');

    for (let i = 0; i < lines.length;) {
      const line = lines[i];

      if (!line.trim()) { i++; continue; }

      const fence = FENCE.exec(line);
      if (fence) {
        const body = [];
        for (i++; i < lines.length && !FENCE.test(lines[i]); i++) body.push(lines[i]);
        i++; // closing fence (or EOF on an unterminated block)
        const pre = tag('pre', { class: 'codebox' });
        pre.append(tag('code', fence[1] ? { class: 'lang-' + fence[1] } : null, body.join('\n')));
        frag.append(pre);
        continue;
      }

      const heading = HEADING.exec(line);
      if (heading) {
        const level = heading[1].length;
        const id = slugify(heading[2], ctx.seen);
        const h = inline(tag('h' + level, { id: id, class: 'md-h' }), heading[2], ctx);
        // A permalink that also gives the reader something to copy; the SPA is hash-routed,
        // so the anchor lives in the route rather than in a bare #fragment.
        if (ctx.anchorHref) h.append(tag('a', { class: 'md-anchor', href: ctx.anchorHref(id) }, '#'));
        ctx.headings.push({ level: level, text: heading[2], id: id });
        frag.append(h);
        i++;
        continue;
      }

      if (HR.test(line)) { frag.append(tag('hr')); i++; continue; }

      const quote = QUOTE.exec(line);
      if (quote) {
        const body = [quote[1]];
        for (i++; i < lines.length; i++) {
          const q = QUOTE.exec(lines[i]);
          if (q) body.push(q[1]);
          else if (lines[i].trim()) body.push(lines[i]); // lazy continuation
          else break;
        }
        const bq = tag('blockquote', { class: 'md-quote' });
        bq.append(render(body.join('\n'), ctx).node);   // ctx is shared, so ids stay unique document-wide
        frag.append(bq);
        continue;
      }

      if (line.trimStart().startsWith('|') && i + 1 < lines.length && TABLE_SEP.test(lines[i + 1])) {
        const align = cells(lines[i + 1]).map(c =>
          c.endsWith(':') ? (c.startsWith(':') ? 'center' : 'right') : null);
        const table = tag('table', { class: 'md-table' });
        const thead = table.appendChild(tag('thead')).appendChild(tag('tr'));
        cells(line).forEach((c, n) => inline(thead.appendChild(cell('th', align[n])), c, ctx));
        const tbody = table.appendChild(tag('tbody'));
        for (i += 2; i < lines.length && lines[i].trimStart().startsWith('|'); i++) {
          const tr = tbody.appendChild(tag('tr'));
          cells(lines[i]).forEach((c, n) => inline(tr.appendChild(cell('td', align[n])), c, ctx));
        }
        frag.append(table);
        continue;
      }

      if (UL.test(line) || OL.test(line)) {
        const ordered = !UL.test(line);
        const list = tag(ordered ? 'ol' : 'ul', { class: 'md-list' });
        const first = ordered ? OL.exec(line)[1] : '1';
        if (first !== '1') list.setAttribute('start', first);
        while (i < lines.length) {
          const m = ordered ? OL.exec(lines[i]) : UL.exec(lines[i]);
          if (!m) break;
          const item = [ordered ? m[2] : m[1]];
          for (i++; i < lines.length; i++) {                 // wrapped continuation lines
            const next = lines[i];
            if (!next.trim() || UL.test(next) || OL.test(next) || HEADING.test(next)
                || FENCE.test(next) || HR.test(next) || next.trimStart().startsWith('|')) break;
            item.push(next.trim());
          }
          inline(list.appendChild(tag('li')), item.join(' '), ctx);
          if (i < lines.length && !lines[i].trim()) {
            // A blank line ends the list unless the next non-blank line continues it.
            let j = i;
            while (j < lines.length && !lines[j].trim()) j++;
            if (j >= lines.length || !(ordered ? OL.test(lines[j]) : UL.test(lines[j]))) break;
            i = j;
          }
        }
        frag.append(list);
        continue;
      }

      const para = [line];
      for (i++; i < lines.length; i++) {
        const next = lines[i];
        if (!next.trim() || HEADING.test(next) || FENCE.test(next) || HR.test(next)
            || QUOTE.test(next) || UL.test(next) || OL.test(next)
            || next.trimStart().startsWith('|')) break;
        para.push(next);
      }
      frag.append(inline(tag('p'), para.join('\n'), ctx));
    }

    return { node: frag, headings: ctx.headings };
  }

  function cells(row) {
    const trimmed = row.trim().replace(/^\|/, '').replace(/\|$/, '');
    return trimmed.split('|').map(c => c.trim());
  }

  function cell(name, align) {
    return tag(name, align ? { style: 'text-align:' + align } : null);
  }

  /** First level-1 heading, used as a document title when the manifest has none. */
  function title(markdown) {
    const m = /^#\s+(.*)$/m.exec(String(markdown));
    return m ? m[1].trim() : null;
  }

  return { render, title, slugify };
})();

if (typeof module !== 'undefined') module.exports = RzMd;
