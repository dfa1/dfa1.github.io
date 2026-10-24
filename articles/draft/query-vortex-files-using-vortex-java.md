# Query Vortex Files Using vortex-java

*24 October 2026*

*In [A Tour of the Vortex Columnar Format with vortex-java](https://dfa1.github.io/articles/a-tour-of-the-vortex-columnar-format-with-vortex-java.html)
I queried a Vortex file twice: with DuckDB's extension and with the CLI's `count`, `stats` and `select`. Those answer a
question you already have. This article is about the step before and the step after: looking at a file to find the
question, and then asking it from the tool you already work in. vortex-java has four ways to do that: `view`, a data
grid in the terminal; `inspect --html`, a report of where the bytes go; `tui`, a browser for how the file is encoded;
and, new in 0.18, a JDBC driver that turns a directory of `.vortex` files into SQL tables for IntelliJ, DBeaver or
anything else that speaks JDBC.*

## Look at the rows: `view`

`view` opens the file as a scrollable grid, like a spreadsheet:

```bash
java -jar vortex-cli-all.jar view eod.vortex
```

![view: eod.vortex as a scrollable grid](query-vortex-files-using-vortex-java-view.png)

<!-- TODO: screenshot of view on eod.vortex -->

Arrows move the cursor, `PgUp`/`PgDn` jump a screen, `g` and `G` go to the first and last row, `q` quits. Opening
the file reads only its footer and the chunk index; rows are decoded when the viewport scrolls into their chunk, and
only the visible window is formatted. So `G` on 4.2 million rows decodes the last chunk, not the 4.2 million rows
before it. `view` also takes an `http(s)://` URL and reads the file with range requests.

## Look at the bytes: `inspect --html`

`inspect` prints the file's layout tree and the encoding of every chunk as text. With `--html` it writes the same
report as a single self-contained page (no scripts, no CDN): where each column's bytes sit in the file, what share of
the file each column costs, and the per-chunk row ranges, min/max and sizes behind those totals.

```bash
java -jar vortex-cli-all.jar inspect --html eod.vortex > report.html
```

The report for `eod.vortex` is here: [eod.vortex inspect report](https://dfa1.github.io/articles/a-tour-of-the-vortex-columnar-format-with-vortex-java-inspect.html).

[![inspect --html report of eod.vortex](https://dfa1.github.io/articles/a-tour-of-the-vortex-columnar-format-with-vortex-java-inspect.png)](https://dfa1.github.io/articles/a-tour-of-the-vortex-columnar-format-with-vortex-java-inspect.html)

Because it is one file, it can be attached to an issue or sent to someone who has neither the data nor the CLI.

## Look at the encodings: `tui`

The text report prints the whole tree at once, which for 13 columns is already long. `tui` shows the same tree as a
browser: a tree pane on the left, the selected node's details on the right.

```bash
java -jar vortex-cli-all.jar tui eod.vortex
```

![tui: the layout tree of eod.vortex](query-vortex-files-using-vortex-java-tui.png)

<!-- TODO: screenshot of tui with an open price column expanded down to alp → for → bitpacked -->

`→` expands a node, `←` collapses it. Everything below the root is loaded on demand: zone-map min/max for a node with
statistics, the entries of a `vortex.dict`, a hex preview of a flat buffer plus its decoded values. It is the quickest
way to answer "why is this column big?": expand it and see which encoding each chunk ended up with.

Neither tool depends on a terminal library. The terminal is driven directly: raw mode through `tcgetattr`/`tcsetattr`
and the window size through `ioctl(TIOCGWINSZ)`, both called with the Foreign Function & Memory API, and
`SetConsoleMode` on Windows.

## Ask from your IDE: the JDBC driver

The terminal is fine for looking. For a real question I want a SQL console, and the one I spend my day in is
IntelliJ's Database tool window, which speaks JDBC and nothing else. `vortex-jdbc-driver` is a JDBC driver in one
self-contained jar (about 29 MB, Calcite and the Vortex modules inside), so any tool that takes a driver jar and a URL
can browse and query `.vortex` files.

It is an embedded engine, like the DuckDB or SQLite drivers: the query runs inside the tool's JVM, which must be JDK 25
or later. It is read-only. Underneath is `vortex-calcite`, an [Apache Calcite](https://calcite.apache.org) adapter;
the driver only turns the URL into its options.

### Set it up

Add the `-all` jar as a custom driver with class `io.github.dfa1.vortex.jdbc.driver.VortexDriver`, then create a data
source with an empty user and password and a URL:

```
jdbc:vortex:/data/vortex
jdbc:vortex:/data/vortex?schema=lake&table.trades=/other/trades.vortex
```

Every `*.vortex` file in the directory is a table named after the file, in a schema called `vtx`; the columns come from
each file's own schema, so there is nothing to declare. `schema` renames `vtx`, and `table.<name>=<path>` adds a file
from elsewhere or overrides a discovered one. Any other key is an error, so a typo cannot silently leave you with an
empty schema. The directory is read when the connection opens: a file added later needs a reconnect. The
[how-to guide](https://github.com/dfa1/vortex-java/blob/master/docs/how-to.md#query-vortex-files-from-an-sql-tool-intellij-dbeaver)
has the step-by-step for IntelliJ and DBeaver.

With `eod.vortex` from the tour in that directory, the table is `vtx.eod`:

```sql
select symbol, count(*), max(high)
from vtx.eod
group by symbol
order by 2 desc;
```

![IntelliJ querying vtx.eod through jdbc:vortex:](query-vortex-files-using-vortex-java-intellij.png)

<!-- TODO: IntelliJ screenshot of vtx.eod in the data grid, plus a measured timing for the query above -->

Double-clicking a table opens it in the data grid, which reads only the page it shows.

### Writing queries

Identifiers are case-sensitive and quoted with double quotes, as IntelliJ and DBeaver generate them. Reserved words
used as column names, such as `open`, `close` or `value`, work unquoted; only `date`, `time`, `timestamp` and
`interval` need quotes, because they start a typed literal (`select "date" from ...`).

The adapter answers the common query shapes from what the file already knows instead of building every row for
Calcite:

| Query | How it is answered |
|---|---|
| `WHERE` on a clustered column | zone-map min/max skip whole chunks |
| `WHERE symbol = 'BTCUSDT'`, `IN`, `<>`, ranges on a dictionary column | decided once per distinct value, then only matching rows are read; a value missing from the dictionary returns at once |
| `LIKE 'prefix%'` | a string range that prunes chunks |
| `GROUP BY` a dictionary, boolean or integer column | folded per dictionary code or group value |
| `COUNT(DISTINCT col)` | counted in the scan |
| `SUM`/`AVG` of a decimal | totalled exactly as unscaled longs |
| `MIN`/`MAX` of a date, time or timestamp | from zone maps |
| `ORDER BY ... LIMIT n` | the first rows kept in a bounded heap during the scan, no full sort |

It is the same idea as in the tour: the encodings already describe the data, so a dictionary column's `GROUP BY` is a
histogram of its codes and its `WHERE` is a lookup in a handful of strings. A query outside these shapes is handed to
Calcite's own engine: same result, only slower.

## Try it

The CLI and the driver are both on
[Maven Central](https://central.sonatype.com/namespace/io.github.dfa1.vortex): `vortex-cli-<version>-all.jar` for
`view`, `inspect` and `tui`, `vortex-jdbc-driver-<version>-all.jar` for your SQL tool. If you have no `.vortex` file yet, the CLI's
`import` turns a CSV into one. And if your tool sends a query the driver chokes on, please
[open an issue](https://github.com/dfa1/vortex-java/issues) with the SQL it generated.
