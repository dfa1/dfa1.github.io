# Review Article Skill

Review a blog article for grammar, prose, and technical issues, then fix all problems in a single pass.

## Standards (from CLAUDE.md)

- **Tone**: informal but neutral — not casual slang, not stiff
- **American English** — never mix British spellings
- **Structure**: narrative → analysis → lesson; not lecture-first
- **Length**: no padding, no summaries of what was just said
- **Claims**: technical assertions need verifiable sources (commits, JEPs, specs, books)
- **Word choice**: precise over hedging; no "it could be argued", "arguably", "perhaps"
- **Redundancy**: flag paragraphs that restate what was already said
- **Dogma/lecture**: flag any passage that moralizes instead of demonstrates
- **Compound modifiers**: hyphenate (e.g. "zero-warnings policy", "type-safe wrapper")
- **Code fences**: must be opened and closed
- **Titles**: prefer actionable/imperative over descriptive observations

## Article structure checklist

- Title: `# H1`
- Date: `*D Month YYYY*` italic, never ISO format (e.g. `*9 May 2026*`)
- Intro: italic paragraph immediately after the date
- Footnotes: named (`[^name]`), anchored at point of use in body — not trailing end-note paragraphs

## Steps

1. Read the article file
2. Check all items above
3. Fix every issue found directly in the file — do not produce a list and ask; just fix
4. Report a short summary of what was changed (one line per fix category, not per fix)

## Rules

- Fix grammar and prose; do not add comments or docstrings to code blocks
- Do not restructure the article unless a section is clearly misplaced
- Do not soften claims — if a claim lacks a source, flag it as a technical issue
- Do not add emojis, headers, or decorative elements
