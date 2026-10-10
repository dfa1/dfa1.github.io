# Vortex on a Real Dataset: 633 MB of Crypto Candles in 127 MB

*10 October 2026*

*[Vortex](https://github.com/vortex-data/vortex) is a columnar file format with a compressor that picks an encoding
per column and per chunk. That compressor follows the BtrBlocks paper[^btrblocks]: keep a handful of simple encodings
and *combine them recursively*, instead of looking for one clever encoding. I wanted to see it work on something real, not a benchmark generator, so I took a year of
public exchange data and looked at what it did to each column. This is a tour of that file, written for someone who is
new to Vortex: what went in, how it is laid out, and which encoding each column ended up with and why.*

## The input

[Binance publishes its market data](https://data.binance.vision) as plain files. I took the 1-minute candlesticks
("klines") for 8 pairs (BTC, ETH, BNB, SOL, XRP, ADA, DOGE, LTC against USDT) for all of 2024, and added the pair name
as a first column. These are the first rows of the file:

```
symbol,open_time,open,high,low,close,volume,close_time,quote_volume,trades,taker_base_volume,taker_quote_volume,ignore
ADAUSDT,1704067200000,0.59390000,0.59410000,0.59320000,0.59400000,104974.20000000,1704067259999,62312.86830000,184,62945.40000000,37368.88100000,0
ADAUSDT,1704067260000,0.59400000,0.59490000,0.59390000,0.59470000,38140.50000000,1704067319999,22675.04514000,87,23698.80000000,14088.40445000,0
ADAUSDT,1704067320000,0.59480000,0.59510000,0.59470000,0.59480000,69129.00000000,1704067379999,41121.03314000,121,47191.90000000,28073.20516000,0
```

Each row is one minute of trading for one pair: the first and last time (`open_time` and `close_time`, in
milliseconds since 1970), the open, high, low and close price, how much was traded, and a few counters. The numbers are
written as fixed-width text (`0.59390000`), which is part of why a CSV is so wasteful.

4,216,320 rows, 13 columns, 633 MB of CSV. It is a good test because it mixes everything a real table has: a
low-cardinality string, timestamps in a fixed rhythm, prices with a few decimals, volumes that are mostly
high-entropy, a counter, and a column that is always zero. Rows are ordered by pair, then time.

To reproduce it:

```bash
for s in BTCUSDT ETHUSDT ...; do for m in 01 ... 12; do
  curl -O https://data.binance.vision/data/spot/monthly/klines/$s/1m/$s-1m-2024-$m.zip
done; done
# unzip, prepend the symbol column and a header line -> eod.csv

java -jar vortex-cli-all.jar import eod.csv eod.vortex
java -jar vortex-cli-all.jar inspect eod.vortex
```

## The result

| | Size | vs CSV | Compress time |
|---|---|---|---|
| CSV | 632.9 MB | 100% | – |
| CSV + `gzip -6` | 175.6 MB | 28% | 13.4 s |
| CSV + `zstd -3` | 187.0 MB | 30% | 1.5 s |
| CSV + `zstd -19` | 140.4 MB | 22% | 188.8 s |
| **Vortex** | **127.2 MB** | **20%** | **1.0 s** |

Compress times are medians of three runs inside one JVM, with JVM start-up and CSV parsing excluded (the Vortex time
is encode and write only). Two things stand out. Vortex beat even
`zstd -19` while taking about a second instead of three minutes, and it did so with no general-purpose compression
at all: every byte is an encoding that knows something about the data. The file is also still a columnar,
random-access format, so a reader can fetch one column, or skip chunks by their min/max statistics, without
decompressing the whole thing. A compressed CSV cannot do either.

## How the file is laid out

Each column becomes a stack of layouts:

```
Struct            one child per column
 └ Zoned          min/max/null-count statistics for every 8,192 rows (zone maps)
    └ Chunked     ~1 MiB chunks: 131,072 rows of an 8-byte column, 49,152 of the string
       └ Flat     one encoded segment each
```

The compressor works on one chunk at a time, so the same column can use different encodings in different places. That
matters here: a chunk that happens to sit inside one trading pair looks nothing like the chunk that straddles two.

## What each column became

| Column | CSV | Vortex | What it is | Encoding of a typical chunk |
|---|---|---|---|---|
| `symbol` | 34.3 MB | **17 KB** | 8 distinct strings, sorted | `constant` (one pair per chunk); `dict` + `runend` where two pairs meet |
| `open_time` | 59.0 MB | **4.0 MB** | one row per minute | `sequence`: two numbers per chunk |
| `close_time` | 59.0 MB | **4.0 MB** | open_time + 59,999 | `sequence` |
| `open` `high` `low` `close` | 52.7 MB each | **8.3 MB** each | prices, 2 to 8 decimals | `alp` → `for` → `bitpacked` |
| `volume` | 58.3 MB | 13.4 MB | traded amount | `alp` with `patches` |
| `taker_base_volume` | 56.8 MB | 12.9 MB | same family | `alp` |
| `quote_volume`, `taker_quote_volume` | 65.5 / 63.9 MB | 27.1 / 25.7 MB | amount × price, long decimals | `alprd`, or `alp` where it fits |
| `trades` | 16.6 MB | 6.7 MB | trade count | `bitpacked` with `patches` |
| `ignore` | 8.4 MB | **5 KB** | always `0` | `constant` |

A tour of the encodings, in the order they appear:

- **`constant`** stores one value for the whole chunk. `ignore` is all zeros, and in a single-pair chunk `symbol` is
  one string; both cost a few bytes per chunk.
- **`sequence`** stores a base and a step. A minute-by-minute timestamp is `base + i × 60000`, so a chunk of 131,072
  of them is two scalars. The 4 MB that is left is the handful of chunks that cross from one pair to the next
  (or a gap in the data) and so are not an exact sequence.
- **`alp`** ("Adaptive Lossless floating-Point"[^alp]) turns decimals back into integers. `0.5939` is stored as `5939` plus the exponents that undo it; values that do not round-trip become
  "patches". The integers are then handled by the next two encodings.
- **`for`** (frame of reference) subtracts the chunk minimum so the numbers get small, and **`bitpacked`** stores
  each in just as many bits as the largest needs, laid out the FastLanes way[^fastlanes]
  so a CPU can decode them with SIMD. An `open` price takes about 16 bits per row where the CSV spends about 12
  characters (96 bits).
- **`alprd`** ("real doubles") is for floats that are not short decimals, such as `quote_volume`, a product of two
  decimals. It splits each 64-bit value into a few high bits, which repeat and go through a small dictionary, and the
  low bits, which are stored packed. It saves less (55 bits per value instead of 64, in a typical chunk), but it is the right tool when ALP cannot help.
- **`dict`** and **`runend`** appear in the `symbol` chunks that are not constant: eight strings, stored once, plus
  small codes, run-length encoded because the same pair repeats thousands of times in a row.

## Encodings that compose

None of the encodings above is clever on its own. The savings come from stacking them, and that is the idea Vortex
takes from BtrBlocks[^btrblocks]. For each chunk the compressor tries every candidate encoding on a small sample and
keeps the best. Then it treats whatever the winner produces (ALP's integers, a dictionary's codes and values) as a new
column and runs the same competition on it, again and again, down to a configurable depth. The Rust crate that does
this is called `vortex-btrblocks`.

You can read the recursion in the tree that `inspect` prints. One `open` price chunk:

```
alp                    decimals -> integers, plus exponents
 └ for                 subtract the chunk minimum
    └ bitpacked        21 bits per value
```

and a `symbol` chunk where one pair ends and the next begins:

```
dict                   8 distinct strings, stored once
 ├ codes:  runend      the same code repeats thousands of times
 └ values: varbin      the strings themselves
```

Nobody told the compressor any of this. There is no schema hint saying "this column is a timestamp" or "these are
prices". Each decision is a measurement on a sample, and each level only has to be good at one small thing. The next
section shows what each level of that stack is worth.

## How much does the cascade matter?

The compressor can stack encodings: ALP turns floats into integers, frame of reference makes those integers small,
bit-packing stores them tightly. How many it may stack is the *cascade depth*, and `3` is the default. I wrote the same
file at each depth to see what each level is worth:

| Cascade depth | Size | vs CSV | Encode + write |
|---|---|---|---|
| 0 (no cascade) | 428.1 MB | 68% | 0.6 s |
| 1 | 129.8 MB | 21% | 1.2 s |
| 2 | 127.2 MB | 20% | 1.1 s |
| 3 (default) | 127.2 MB | 20% | 1.0 s |

Medians of three runs in one JVM. The three cascading rows are within noise of each other on time. Per column:

| Column | depth 0 | depth 1 | depth 2 and 3 |
|---|---|---|---|
| `symbol` | 8.5 MB | 4 KB | 4 KB |
| `open_time`, `close_time` | 33.7 MB | 4.0 MB | 4.0 MB |
| `open` (and `high`, `low`, `close`) | 34.0 MB | 9.0 MB | 8.3 MB |
| `volume` | 34.1 MB | 13.4 MB | 13.4 MB |
| `trades` | 33.7 MB | 6.7 MB | 6.7 MB |
| `ignore` | 33.7 MB | 5 KB | 5 KB |

What this shows:

- **Depth 0 is the format without the compressor.** Each value takes its full width, 8 bytes, about 33.7 MB a column.
  ALP is even chosen for the prices, but with nowhere to send its integers the chunk is the same size as raw. It is a
  useful baseline: Vortex still lays the file out in zones and chunks, you just give up the encodings.
- **Depth 1 does almost all the work.** One encoding per chunk is enough for `sequence`, `constant` and `dict`, which
  is why timestamps, `symbol` and `ignore` are already at their final size. For prices, ALP can pass its integers to
  bit-packing, and 129.8 MB is already within 2% of the best.
- **Depth 2 pays only for prices.** With one more level, ALP's integers go through frame of reference first, which
  subtracts the chunk minimum and saves two bits per value (23 down to 21 in a typical chunk): 9.0 MB to 8.3 MB per
  price column.
- **Depth 3 buys nothing here.** This file has no encoding that wants a fourth layer. It cost nothing measurable
  (128 bytes more, no extra time), and a deeper default leaves room for nested cases, such as a dictionary whose
  values are floats.

Time is within noise across 1, 2 and 3 on this file, so the extra levels were free here.

## What can you do with the file?

Smaller is nice, but the file is also queryable without being unpacked first. The most convenient way is
[DuckDB](https://duckdb.org), which has a Vortex extension:

```sql
INSTALL vortex;
LOAD vortex;

-- the closing price of BTC for one hour, 12:00 to 13:00 UTC on 15 June 2024
SELECT open_time, close
FROM read_vortex('eod.vortex')
WHERE symbol = 'BTCUSDT'
  AND open_time >= 1718452800000 AND open_time < 1718456400000
ORDER BY open_time;
```

```
┌───────────────┬──────────┐
│   open_time   │  close   │
│     int64     │  double  │
├───────────────┼──────────┤
│ 1718452800000 │  66328.0 │
│ 1718452860000 │ 66338.39 │
│ 1718452920000 │ 66338.39 │
│      ...      │   ...    │      60 rows in all
│ 1718456340000 │ 66348.01 │
└───────────────┴──────────┘
```

That takes 0.03 s, DuckDB start-up and extension load included. The same query on the CSV, through `read_csv('eod.csv')`,
takes 0.30 s: DuckDB's CSV reader is quick and parallel, so that is a fair baseline, and the Vortex file is still about
ten times faster, from a file one fifth the size.

### Reading a file another implementation wrote

There is a second reason to run this query. I produced `eod.vortex` with a Java implementation of the format
([vortex-java](https://github.com/dfa1/vortex-java)), and DuckDB's extension is the `vortex-duckdb` crate of the Rust
reference. So the query also checks that two independent implementations agree on the bytes.

One query is a weak check, so I compared the whole table. For each of the 13 columns I summed a hash of every value,
once reading the CSV and once reading the Vortex file:

```sql
SELECT count(*) AS n,
       sum(hash(symbol)::HUGEINT) AS h_symbol,
       sum(hash(open_time)::HUGEINT) AS h_open_time,
       ...
FROM read_csv('eod.csv', header = true);       -- and the same over read_vortex('eod.vortex')
```

The row count and all 13 sums are identical, so every one of the 4,216,320 values in 13 columns comes back exactly as
it went in.

### Without DuckDB: the CLI

The CLI that ships with vortex-java does the basics. How many rows, and what range does each column cover?

```
$ java -jar vortex-cli-all.jar count eod.vortex          # 0.06 s
4216320
$ java -jar vortex-cli-all.jar stats eod.vortex
column                type                      min              max
symbol                utf8                  ADAUSDT          XRPUSDT
open_time             i64             1704067200000    1735689540000
open                  f64                   0.07434        108258.38
...
```

The row count is stored in the file's layout metadata, so `count` takes 0.06 s, JVM start-up included, without reading
the data. `wc -l` is the fair comparison for a CSV: it takes 0.44 s for these 633 MB when the file is already in the
page cache, because it has to read every byte to find the newlines (and it says 4216321, since it counts the header).

`select` prints chosen columns, and `--where` (repeatable, all conditions must hold) keeps only some rows. The same
question as above is one command:

```
$ java -jar vortex-cli-all.jar select eod.vortex open_time close \
    --where "symbol = BTCUSDT" --where "open_time >= 1718452800000" --where "open_time < 1718456400000"
open_time,close
1718452800000,66328.0
1718452860000,66338.39
...                                                          # 60 rows
```

It takes 0.18 s with the JVM start-up included, and returns the same 60 rows. Two details: string values go unquoted in
the expression, and the pair is the selective filter on this file, since it is sorted by pair and then time.

## Try it on your own data

Pick a CSV you already know well, import it, run `inspect`, and read the per-column encodings. The column whose
encoding surprises you is the one worth investigating.

## Detail: where I found out how it works

I came to this file while writing [vortex-java](https://github.com/dfa1/vortex-java), a native Java implementation.
My first writer produced 155 MB from these same rows, 22% more than the reference, and since both files decode to
identical CSV, the whole gap was in which encodings were chosen. Comparing the per-column sizes and then the
per-chunk encoding trees against the reference showed where: for example, my compressor never offered `sequence` as a
candidate, and the sample it measures on has to be the very one the reference draws. Closing the gap is what taught me
most of what is in this article. The story is in [issue #507](https://github.com/dfa1/vortex-java/issues/507).

[^alp]: Azim Afroozeh, Leonardo Kuffó and Peter Boncz, [ALP: Adaptive Lossless floating-Point Compression](https://dl.acm.org/doi/10.1145/3626717), SIGMOD 2024.
[^fastlanes]: Azim Afroozeh and Peter Boncz, [The FastLanes Compression Layout](https://www.vldb.org/pvldb/vol16/p2132-afroozeh.pdf), VLDB 2023.
[^btrblocks]: Maximilian Kuschewski, David Sauerwein, Adnan Alhomssi and Viktor Leis, BtrBlocks: Efficient Columnar Compression for Data Lakes, SIGMOD 2023.
