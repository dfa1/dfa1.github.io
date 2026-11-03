# Let the Encoding Answer the Query

*24 October 2026*

*In [A Tour of the Vortex Columnar Format with vortex-java](https://dfa1.github.io/articles/a-tour-of-the-vortex-columnar-format-with-vortex-java.html)
every column of a 4.2-million-row file ended up as a stack of encodings that know something about the data: a
dictionary of eight strings, min/max per zone, a timestamp stored as base plus step. I queried that file from DuckDB and
the CLI, but the SQL console I actually spend my day in is IntelliJ's, and it speaks JDBC and nothing else. So
vortex-java 0.18 ships a JDBC driver, and the interesting part of it is how little it has to compute: most of the work
of a query was done when the file was written. The encodings are an index you got for free.*

## A SQL console for a directory of files

`vortex-jdbc-driver` is one self-contained jar (about 29 MB, [Apache Calcite](https://calcite.apache.org) and the
Vortex modules inside). It is an embedded, read-only engine like the DuckDB or SQLite drivers: the query runs inside
the tool's JVM, which must be JDK 25 or later. Add the jar as a custom driver with class
`io.github.dfa1.vortex.jdbc.driver.VortexDriver`, leave user and password empty, and point the URL at a directory:

```
jdbc:vortex:/data/vortex
```

Every `*.vortex` file in it becomes a table in the schema `vtx`, with columns taken from the file's own schema, so
there is nothing to declare. The [how-to guide](https://github.com/dfa1/vortex-java/blob/master/docs/how-to.md#query-vortex-files-from-an-sql-tool-intellij-dbeaver)
covers the remaining options and the step-by-step for IntelliJ and DBeaver.

With `eod.vortex` from the tour in that directory, the first question is how the eight pairs compare:

```sql
select symbol, count(*), max(high)
from vtx.eod
group by symbol
order by 2 desc;
```

![IntelliJ querying vtx.eod through jdbc:vortex:](let-the-encoding-answer-the-query-intellij.png)

<!-- TODO: measured time for this query in IntelliJ -->

Underneath is `vortex-calcite`, a Calcite adapter. The simplest adapter would decode every chunk, hand Calcite one
row per record and let its engine filter, group and sort. That is correct, but most of the decoding is wasted, because
the file can answer most of the question without it.

## What the file already knows

`tui` shows the layout tree of a file, so it is the quickest way to see what a query could use instead of rows:

```bash
java -jar vortex-cli-all.jar tui eod.vortex
```

![tui: the symbol column of eod.vortex](let-the-encoding-answer-the-query-tui.png)

<!-- TODO: screenshot of tui with symbol expanded: zone min/max, one constant chunk, one dict chunk -->

Three facts about `symbol` are visible there:

- every zone of 8,192 rows records its min and max, and since the file is sorted by pair, almost every zone holds one
  pair;
- most chunks are `constant`: one pair, stored once;
- the chunks where two pairs meet are a `dict` of a handful of strings plus run-length-encoded codes.

Each of these answers a part of the query above. A `WHERE symbol = 'BTCUSDT'` can skip every zone whose min/max range
excludes it, which is seven eighths of the file. A dictionary chunk can evaluate the predicate once per distinct
string instead of once per row, and if the string is not in any dictionary there is nothing to read at all. A
`GROUP BY symbol` over a dictionary chunk is a histogram of its codes. The adapter turns each of these into a Calcite
planner rule:

| Query | How it is answered |
|---|---|
| `WHERE` on a clustered column | zone-map min/max skip whole chunks |
| `WHERE symbol = 'BTCUSDT'`, `IN`, `<>`, ranges on a dictionary column | decided once per distinct value, then only matching rows are read |
| `LIKE 'prefix%'` | a string range that prunes chunks |
| `GROUP BY` a dictionary, boolean or integer column | folded per dictionary code or group value |
| `COUNT(DISTINCT col)` | counted in the scan |
| `SUM`/`AVG` of a decimal | totalled exactly as unscaled longs |
| `MIN`/`MAX` of a date, time or timestamp | from zone maps |
| `ORDER BY ... LIMIT n` | the first rows kept in a bounded heap during the scan, no full sort |

A query outside these shapes is handed to Calcite's own engine: same result, only slower.

<!-- TODO: verify GROUP BY symbol is folded on eod.vortex. GroupedAggregate only accepts VarBinDictArray for strings,
and most symbol chunks are vortex.constant (CLI import disables the global dictionary). If constant chunks decline,
the headline query falls back to Calcite and this section needs another example (or a fix in vortex-java first). -->

## How much it saves

<!-- TODO: A/B over the driver. Baseline = the same query rewritten so no rule matches (e.g. group by lower(symbol),
where lower(symbol) = 'btcusdt'); confirm with EXPLAIN PLAN FOR that the baseline really is a plain scan. Median of
three runs, connection already open. -->

| Query | Pushed down | Calcite engine | Plan |
|---|---|---|---|
| `count(*)`, `max(high)` group by `symbol` | TODO | TODO | grouped fold |
| `count(*)` where `symbol = 'BTCUSDT'` | TODO | TODO | zone-map pruning + dictionary lookup |
| top 10 minutes by `trades` (`order by trades desc limit 10`) | TODO | TODO | bounded heap |

<!-- TODO: one paragraph reading the numbers: which win is largest and why, which one is close to parity and why -->

Pruning by statistics is not new; Parquet readers skip row groups the same way. The difference is how far it goes
when the encodings themselves are the statistics. Each rule is a lookup into something the writer already computed to
make the file small, so the cost of the index was paid at write time, by the compressor, for a different reason.

## Gotchas from IntelliJ

Identifiers are case-sensitive and quoted with double quotes, as IntelliJ and DBeaver generate them. Reserved words
used as column names, such as `open`, `close` or `value`, work unquoted; only `date`, `time`, `timestamp` and
`interval` need quotes, because they start a typed literal (`select "date" from ...`). The directory is read when the
connection opens, so a file added later needs a reconnect.

## Try it

The driver and the CLI are both on [Maven Central](https://central.sonatype.com/namespace/io.github.dfa1.vortex):
`vortex-jdbc-driver-<version>-all.jar` for your SQL tool, `vortex-cli-<version>-all.jar` for `tui`, `view` (a data
grid in the terminal that decodes only the chunks on screen) and `inspect --html` (a self-contained report of where
the bytes go, like [the one for eod.vortex](https://dfa1.github.io/articles/a-tour-of-the-vortex-columnar-format-with-vortex-java-inspect.html)).
If you have no `.vortex` file yet, the CLI's `import` turns a CSV into one. And if your tool sends a query the driver
chokes on, please [open an issue](https://github.com/dfa1/vortex-java/issues) with the SQL it generated.
