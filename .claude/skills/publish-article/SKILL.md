# Publish Article Skill

Move a draft article to production and wire it into the index, feed, and sitemap.

## Input

The user provides the article slug or filename (e.g. `the-cost-of-implicitness` or
`articles/draft/the-cost-of-implicitness.md`). Derive the slug from whatever form is given.

## Steps

### 1. Read the draft

Read `articles/draft/<slug>.md` and extract:
- **Title**: the `# H1` heading (first line)
- **Date string**: the `*D Month YYYY*` italic line (second non-empty line), e.g. `9 May 2026`
- **Year**: parsed from the date string
- **ISO date**: convert date string to `YYYY-MM-DD` (e.g. `2026-05-09`)
- **Intro**: the italic paragraph that follows the date (the `*...*` block); strip the surrounding `*` and use as plain text for the feed `<summary>`

### 2. Move the file

Rename `articles/draft/<slug>.md` → `articles/<slug>.md` using `git mv` so the move is tracked.

### 3. Update `index.md`

In the `## Writings` section, prepend a new bullet **at the top** of the list:

```
- [<Title>](articles/<slug>) (<Year>)
```

Also update the `Updated:` line at the bottom of `index.md` to the article's date formatted as
`D Month YYYY` (e.g. `30 April 2026`).

### 4. Update `feed.xml`

- Change `<updated>` on the feed root element to `<ISO date>T00:00:00Z`
- Insert a new `<entry>` block **after the opening `<feed>` metadata and before the first existing `<entry>`**:

```xml
  <entry>
    <title><Title></title>
    <link href="https://dfa1.github.io/articles/<slug>"/>
    <id>https://dfa1.github.io/articles/<slug></id>
    <updated><ISO date>T00:00:00Z</updated>
    <published><ISO date>T00:00:00Z</published>
    <author>
      <name>Davide Angelocola</name>
    </author>
    <summary><intro plain text></summary>
  </entry>
```

Escape `&` as `&amp;` in the summary if needed.

### 5. Update `sitemap.xml`

Append a new `<url>` block **before the closing `</urlset>` tag**:

```xml
  <url>
    <loc>https://dfa1.github.io/articles/<slug></loc>
    <lastmod><ISO date></lastmod>
  </url>
```

### 6. Commit

Stage all modified files by name:
- `articles/<slug>.md`
- `articles/draft/<slug>.md` (the deletion)
- `index.md`
- `feed.xml`
- `sitemap.xml`

Commit message: `publish: <slug>` (conventional commits, subject ≤50 chars).

Do **not** push — leave that for the user.

## Rules

- Never guess the intro text — read it from the file
- The `<summary>` must be plain text (no Markdown, no HTML tags, no footnote anchors like `[^name]`)
- Do not add the article to the sitemap if it is already there
- Do not push without explicit user confirmation (branch is master)
