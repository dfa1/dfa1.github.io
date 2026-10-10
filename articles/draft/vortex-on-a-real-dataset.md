# Vortex on a Real Dataset: 633 MB of Crypto Candles in 127 MB

*10 October 2026*

*[Vortex](https://github.com/vortex-data/vortex) is a columnar file format with a compressor that picks an encoding
per column and per chunk. I wanted to see it work on something real, not a benchmark generator, so I took a year of
public exchange data and looked at what it did to each column. This is a tour of that file, written for someone who is
new to Vortex: what went in, how it is laid out, and which encoding each column ended up with and why.*

## The input

[Binance publishes its market data](https://data.binance.vision) as plain files. I took the 1-minute candlesticks
("klines") for 8 pairs (BTC, ETH, BNB, SOL, XRP, ADA, DOGE, LTC against USDT) for all of 2024, and added the pair name
as a first column:

```
symbol,open_time,open,high,low,close,volume,close_time,quote_volume,trades,taker_base_volume,taker_quote_volume,ignore
ADAUSDT,1704067200000,0.59390000,0.59410000,0.59320000,0.59400000,104974.20000000,1704067259999,62312.86830000,184,...
```

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
| **Vortex** | **127.1 MB** | **20%** | **1.3 s** |

Compress times are warm runs inside one JVM, JVM start-up excluded. Two things stand out. Vortex beat even
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

The part I like most is that nobody told the compressor any of this. There is no schema hint saying "this column is
a timestamp". For every chunk it compresses a small sample with each candidate encoding and keeps the best, then
repeats the choice for each child of the winner (ALP's integers, a dictionary's codes), up to three levels deep.

## A note for people implementing Vortex

I wrote this while working on [vortex-java](https://github.com/dfa1/vortex-java), a native Java implementation, and
this file turned out to be a useful oracle. My first version of the writer produced 155 MB, 22% more than the
reference's 127 MB, from the same rows. Because Vortex is lossless, both files decoded to identical CSV, so the whole
difference was in the choice of encoding, and that is the kind of thing you can debug by diffing:

1. Sum the bytes per column in each file: two timestamp columns were 27 of the 28 MB.
2. Print the encoding tree per chunk for one column and compare: my compressor never offered `sequence` as a
   candidate.
3. Repeat for the next column. The causes were small and specific: the sample the compressor draws should be the
   exact one the reference draws (a fixed-seed generator), the Rust file writer keeps integer dictionaries out of the
   per-chunk choice, a dictionary's value pool should itself be compressed, a string chunk with one repeated value
   should be a `constant`, and exceptions in ALP are stored unpacked.

Seen from the outside, the reference behaves consistently: the same data gives the same tree every time, and the
choices follow from the numbers. That made it a good teacher. The details are in
[issue #507](https://github.com/dfa1/vortex-java/issues/507) if you are curious.

## Try it on your own data

Pick a CSV you already know well, import it, run `inspect`, and read the per-column encodings. The column whose
encoding surprises you is the one worth investigating.

[^alp]: Azim Afroozeh, Leonardo Kuffó and Peter Boncz, [ALP: Adaptive Lossless floating-Point Compression](https://dl.acm.org/doi/10.1145/3626717), SIGMOD 2024.
[^fastlanes]: Azim Afroozeh and Peter Boncz, [The FastLanes Compression Layout](https://www.vldb.org/pvldb/vol16/p2132-afroozeh.pdf), VLDB 2023.