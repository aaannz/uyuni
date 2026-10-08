#!/usr/bin/env python3
"""Regenerate the presentation HTML variants of the autoinst_plan documents.

Usage: .venv/bin/python autoinst_plan/build_html.py
Reads 00-goals.md / 10-roadmap.md / 20-kernel-options.md and writes the
same-named .html files.
"""
import pathlib
import markdown

HERE = pathlib.Path(__file__).parent

CSS = """
:root {
  --bg: #f6f7f9; --paper: #ffffff; --ink: #1f2430; --muted: #6b7280;
  --accent: #30ba78; --accent-dark: #0c6b3d; --border: #e3e6ea;
  --code-bg: #f0f2f5; --chip: #eef6f1;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body {
  margin: 0; background: var(--bg); color: var(--ink);
  font: 15px/1.65 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
        "Helvetica Neue", Arial, sans-serif;
}
.layout { display: flex; max-width: 1400px; margin: 0 auto; }
nav#toc {
  width: 280px; flex-shrink: 0; position: sticky; top: 0;
  max-height: 100vh; overflow-y: auto; padding: 28px 18px;
  border-right: 1px solid var(--border); background: var(--paper);
}
nav#toc .toc-title {
  font-size: 11px; letter-spacing: .12em; text-transform: uppercase;
  color: var(--muted); margin: 0 0 10px;
}
nav#toc a {
  display: block; padding: 3px 8px; border-left: 2px solid transparent;
  color: var(--ink); text-decoration: none; font-size: 13px; border-radius: 0 4px 4px 0;
}
nav#toc a:hover { background: var(--chip); }
nav#toc a.h2 { font-weight: 600; }
nav#toc a.h3 { padding-left: 24px; color: var(--muted); font-size: 12.5px; }
nav#toc a.active { border-left-color: var(--accent); background: var(--chip); }
main { flex: 1; min-width: 0; padding: 40px 56px 80px; }
article { max-width: 900px; margin: 0 auto; background: var(--paper);
  border: 1px solid var(--border); border-radius: 10px; padding: 48px 56px; }
h1 { font-size: 27px; line-height: 1.25; margin: 0 0 6px; }
h1 + p { color: var(--muted); }
h2 {
  font-size: 20px; margin: 40px 0 12px; padding-top: 18px;
  border-top: 1px solid var(--border);
}
h3 { font-size: 16px; margin: 26px 0 8px; }
h4 { font-size: 14px; margin: 20px 0 6px; }
a { color: var(--accent-dark); }
blockquote {
  margin: 14px 0; padding: 10px 16px; background: var(--chip);
  border-left: 4px solid var(--accent); border-radius: 0 6px 6px 0;
}
blockquote p { margin: 4px 0; }
code {
  background: var(--code-bg); border: 1px solid var(--border);
  border-radius: 4px; padding: 1px 5px; font-size: 12.5px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  overflow-wrap: anywhere;
}
pre {
  background: #23272e; color: #e6e9ef; padding: 14px 18px; border-radius: 8px;
  overflow-x: auto; line-height: 1.5;
}
pre code { background: none; border: none; color: inherit; padding: 0; font-size: 12.5px; }
table { border-collapse: collapse; width: 100%; margin: 14px 0; font-size: 13.5px; }
th, td { border: 1px solid var(--border); padding: 7px 10px; text-align: left; vertical-align: top; }
th { background: var(--chip); font-weight: 600; }
tbody tr:nth-child(even) { background: #fafbfc; }
hr { border: none; border-top: 1px solid var(--border); margin: 28px 0; }
ul, ol { padding-left: 24px; }
li { margin: 3px 0; }
.doc-meta table { width: auto; }
footer.note {
  max-width: 900px; margin: 14px auto 0; color: var(--muted);
  font-size: 12px; text-align: center;
}
.menu-toggle, #toc-backdrop { display: none; }
@media (max-width: 980px) {
  .layout { display: block; }
  nav#toc {
    position: fixed; inset: 0 auto 0 0; width: 290px; z-index: 30;
    transform: translateX(-100%); transition: transform .2s ease; box-shadow: 0 0 24px rgba(0,0,0,.15);
  }
  body.toc-open nav#toc { transform: none; }
  .menu-toggle {
    display: block; position: fixed; top: 12px; right: 12px; z-index: 40;
    background: var(--paper); border: 1px solid var(--border); border-radius: 8px;
    padding: 8px 12px; font-size: 14px; cursor: pointer;
  }
  #toc-backdrop {
    display: block; position: fixed; inset: 0; z-index: 20;
    background: rgba(0,0,0,.25); opacity: 0; pointer-events: none; transition: opacity .2s;
  }
  body.toc-open #toc-backdrop { opacity: 1; pointer-events: auto; }
  main { padding: 20px; }
  article { padding: 26px 20px; }
}
@media print {
  nav#toc, .menu-toggle, #toc-backdrop, footer.note { display: none; }
  body { background: #fff; }
  .layout { display: block; max-width: none; }
  main { padding: 0; }
  article { border: none; padding: 0; max-width: none; }
  pre { white-space: pre-wrap; }
  h2 { break-after: avoid; }
  table, blockquote, pre { break-inside: avoid; }
}
"""

JS = """
document.addEventListener('DOMContentLoaded', function () {
  var body = document.body;
  var toggle = document.querySelector('.menu-toggle');
  var backdrop = document.getElementById('toc-backdrop');
  if (toggle) toggle.addEventListener('click', function () { body.classList.toggle('toc-open'); });
  if (backdrop) backdrop.addEventListener('click', function () { body.classList.remove('toc-open'); });
  var links = Array.prototype.slice.call(document.querySelectorAll('nav#toc a'));
  var heads = links.map(function (a) { return document.getElementById(a.getAttribute('href').slice(1)); });
  function highlight() {
    var idx = -1, y = window.scrollY + 90;
    for (var i = 0; i < heads.length; i++) { if (heads[i] && heads[i].offsetTop <= y) idx = i; }
    links.forEach(function (a, i) { a.classList.toggle('active', i === idx); });
  }
  window.addEventListener('scroll', highlight, { passive: true });
  highlight();
});
"""

TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} — Autoinstallation Overhaul</title>
<style>{css}</style>
</head>
<body>
<button class="menu-toggle" type="button" aria-label="Toggle table of contents">&#9776; Contents</button>
<div id="toc-backdrop"></div>
<div class="layout">
<nav id="toc" aria-label="Table of contents">
<p class="toc-title">{title}</p>
{toc}
</nav>
<main>
<article>
{body}
</article>
<footer class="note">Generated from <code>{src}</code> — do not edit by hand; run
<code>autoinst_plan/build_html.py</code> to regenerate.</footer>
</main>
</div>
<script>{js}</script>
</body>
</html>
"""


def render_toc(tokens):
    lines = []

    def walk(children):
        for tok in children:
            if tok["level"] > 3:
                continue
            lines.append(
                '<a class="h{lvl}" href="#{id}">{name}</a>'.format(
                    lvl=tok["level"], id=tok["id"], name=tok["name"]
                )
            )
            if tok["level"] < 3:
                walk(tok["children"])

    walk(tokens)
    return "\n".join(lines)


def build(md_path: pathlib.Path) -> pathlib.Path:
    text = md_path.read_text()
    md = markdown.Markdown(
        extensions=["tables", "fenced_code", "toc", "sane_lists", "smarty"],
        extension_configs={"toc": {"toc_depth": "2-3"}},
    )
    body = md.convert(text)
    html = TEMPLATE.format(
        title=md_path.stem,
        css=CSS,
        js=JS,
        toc=render_toc(md.toc_tokens),
        body=body,
        src=md_path.name,
    )
    out = md_path.with_suffix(".html")
    out.write_text(html)
    return out


if __name__ == "__main__":
    for name in ("00-goals.md", "10-roadmap.md", "20-kernel-options.md"):
        path = HERE / name
        print(f"{path} -> {build(path)}")
