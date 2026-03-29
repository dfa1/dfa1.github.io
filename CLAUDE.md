# CLAUDE.md

## About this project

Personal website and blog of Davide Angelocola — Technical Lead/Senior Software Engineer based in Zürich, Switzerland. The site is a static site hosted on GitHub Pages. Articles are written in Markdown under `articles/`.

## Author

- Name: Davide Angelocola
- GitHub: [@dfa1](https://github.com/dfa1)
- Primary project: [hosh](https://github.com/hosh-shell/hosh), a Human Oriented Shell written in Java

## Writing style

- **Tone**: informal but neutral — conversational, not casual slang; direct, not stiff
- **Claims must be supported by facts**: technical assertions should reference commits, JEPs, specs, or other verifiable sources; avoid vague generalisations
- **Structure**: narrative first (what happened, what went wrong, why), then analysis, then lesson — not lecture-first
- **Length**: concise; no padding, no summaries of what was just said
- **Code examples**: include only what is necessary to illustrate the point; prefer real code from the project over toy examples

## Editorial standards

- Fix grammar and prose issues directly; do not add docstrings or comments to code that wasn't changed
- Prefer precise word choice over hedging language
- Avoid redundant paragraphs that restate what was already explained
- Hyphenate compound modifiers (e.g. "zero-warnings policy", "FFM-based terminal")
- Code fences must always be properly closed

## Repository layout

```
/                     → site root (index.md, favicon, robots.txt, sitemap.xml)
articles/             → blog posts in Markdown
```
