---
description: Review a blog article for grammar, prose, and technical issues, then fix all problems in a single pass
argument-hint: <path-to-article>
allowed-tools: [Read, Edit, Glob, Grep, Bash]
---

# Review Article

Article to review: $ARGUMENTS

## Instructions

1. **Read** the full article at the given path. If no path given, ask the user which article to review.

2. **Check for issues** across these categories — collect ALL issues before fixing anything:
   - Grammar and spelling (American English only — never British spellings)
   - Prose: hedging language, padding, redundant paragraphs that restate what was just said
   - Technical accuracy: every technical claim must be verifiable; flag unsupported assertions
   - Anachronisms: do not add clarifications that reference technology that didn't exist at the time described
   - Structure: title present, date as italic, short italic intro present
   - Formatting: compound modifiers must be hyphenated (e.g. "zero-copy", "user-space"), all code fences properly closed, no broken markdown
   - Tone: warn if article is dogmatic or lecture-style rather than narrative-first

3. **Report** all issues found as a numbered list before making any changes. Group by category.

4. **Fix all issues in a single pass** — do not ask for confirmation between fixes. Apply every fix directly.

5. **Do NOT**:
   - Add docstrings, comments, or annotations to code that wasn't changed
   - Add anachronistic clarifications
   - Add summaries of what was just said
   - Refuse an explicit user request — if the user asks for a section, add it

6. After fixing, **commit** with a short conventional commit message: `fix: review corrections for <article-name>`
