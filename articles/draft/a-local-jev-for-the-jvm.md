# A Local Jev for the JVM

*2 October 2026*

*[TypeSafe](https://docs.typesafe.ai/introduction)'s Jev answers typed questions about a piece of state, a yes/no
probability, a choice over options, a score on a scale, without generating text. I maintain a Java client for it,
[typesafe-java](https://github.com/dfa1/typesafe-java). The obvious next question was whether the same API could run
in-process, with no network and no API key. Jev's weights aren't public, so "local Jev" can only mean the same
contract with a different model behind it. This is how two days of trying went, and what the numbers say.*

## The contract is the easy part

`TypeSafeClient` is an interface, so a local engine is just another implementation. Everything built on top keeps
working: typed records via `MappingTypeSafeClient`, deadlines, token counting, test doubles.

```java
try (TypeSafeClient client = LocalTypeSafeClient.laya(modelDir)) {   // was: TypeSafeClient.builder(apiKey).build()
    EvaluateResponse r = client.evaluate(EvaluateRequest.of(
            Content.text("Help! My payouts have been failing for 3 days."),
            Map.of("is_urgent", Question.noul("Does this convey urgency?"))));
    r.nouls().get("is_urgent").noul(); // 0.0–1.0
}
```

The hard part is the model.

## First attempt: read an LLM's next-token scores

Jev's answers are probability distributions, and a causal LM already produces one: the logits of its next token. Ask
"Answer with Yes or No", run a single prefill with no generation, and softmax over the `Yes` and `No` logits. Choice uses
option letters, Score uses level digits. It's deterministic, and there's nothing to parse.

I built it on [inference4j](https://github.com/inference4j), which exposes exactly that prefill. Two of its tokenizer
bugs cost me most of a day:

- `BpeTokenizer` runs `text.strip().replaceAll("\\s+", " ")` on every input, which deletes every newline in a ChatML
  prompt. The model never saw "assistant⏎" and kept trying to emit the newline instead of answering.
- It reads special-token ids from `vocab.json` only. Qwen keeps `<|im_start|>` and friends in `added_tokens.json`, so
  the chat markers were silently split into plain text.

Both are worked around with a small wrapper. The model size question was harder: SmolLM2-360M said "Yes" to everything,
Qwen2.5-0.5B ranked a furious message calmer than a polite one, and Qwen2.5-1.5B worked, at about 1.2 questions per
second on an Apple M5.

## Measure against the real thing

"Seems to work" on four sanity checks isn't a result. I built a small harness: 24 support messages, three questions
each (urgent? which category? how angry?), sent once to the real API (`jev-1.13.0`) and cached, then replayed against
any local engine. It reports yes/no agreement, choice agreement, score error and correlation.

## Parallelism doesn't help a compute-bound CPU

Before changing models I tried to buy throughput with concurrency. None of it worked:

| 3 questions, Qwen2.5-1.5B | time |
|---|---|
| sequential, 1 session × 10 threads | 1,598 ms |
| one batch of 3 | 1,673 ms |
| 3 sessions × 3 threads | 1,682 ms |
| 10 sessions × 1 thread (throughput) | about half, and 3× the RAM |

Prefill is matrix-matrix work that already saturates every core. Splitting the same FLOPs differently doesn't finish
sooner, and N sessions means N copies of the weights competing for memory bandwidth. The only lever is less work per
answer.

## What others do

There's a small ecosystem of open "Jev-class" models, and they fall into three families:

1. **Frozen LLM, read the logits.** What I did. It works, but it needs about 12B parameters to get close.
2. **LLM plus trained decision heads.** Kev, NanoJev, a few 4B models. Better, but still an LLM's cost.
3. **A purpose-built encoder.** [Laya](https://github.com/NandhaKishorM/laya) is a 421M ModernBERT with a small head
   trained for Choice/Score/Noul. Every option gets a `[MASK]` marker and the head scores the markers. Apache-2.0.

Every implementation I found is Python, with ports in TypeScript and Go. I didn't find one for the JVM.

## Laya, from Java

Laya ships PyTorch weights and a custom head, so the port took three pieces:

- **A one-off ONNX export** of encoder plus head as one graph. The legacy `torch.onnx` tracer baked the sample's sequence
  length into the head's attention reshapes; the dynamo exporter keeps the shapes dynamic.
- **The exact tokenizer.** ModernBERT is byte-level BPE, and a leading space in each option is significant, so
  inference4j's tokenizer wasn't an option. I started with [DJL](https://djl.ai)'s binding of Hugging Face
  `tokenizers`, which reads `tokenizer.json` exactly.
- **A line-by-line port** of Laya's sequence builder and post-processing, with a fixture test that checks Java's token
  ids and logits against PyTorch's.

## Down to two dependencies

By then the experiment worked, but it pulled in two libraries I no longer wanted.

- **inference4j.** I dropped it. Qwen's single prefill is about twenty lines of plain ONNX Runtime: token ids, an
  attention mask, positions, and an empty KV cache per layer. Asking ONNX Runtime for the `logits` output only, instead
  of every cache tensor too, made Qwen faster than it was on inference4j.
- **DJL.** I dropped that too. It's an 18 MB jar plus a native library unpacked at run time, there to tokenize. Both
  models use the same kind of tokenizer, byte-level BPE described by `tokenizer.json`: NFC normalization, added tokens
  first, a pre-tokenizing regex, GPT-2's byte-to-character table, then merges by rank. That's about 200 lines of Java.
  It refuses any `tokenizer.json` option it doesn't implement, and a test checks it id-for-id against Hugging Face's
  own library on 563 strings: every string the engines tokenize for the comparison set, plus emoji, accents, CJK and
  runs of spaces. The one trap: Java's `\s` is ASCII-only unless you pass `UNICODE_CHARACTER_CLASS`, and Rust's isn't.

What's left is `typesafe-java-core` and ONNX Runtime. The model files come from a local directory, and no network is
needed at run time.

```java
TypeSafeClient laya = LocalTypeSafeClient.laya(Path.of("models/laya-int8"));
TypeSafeClient qwen = LocalTypeSafeClient.qwen(Path.of("models/qwen2.5-1.5b"));
```

## Results

Against `jev-1.13.0` on the 24 cached requests, with throughput on an Apple M5 CPU:

| engine | questions/s | urgent (same yes/no) | category (same pick) | anger: error, r |
|---|---|---|---|---|
| Qwen2.5-1.5B, next-token scores | ~1.2 | 15/24 | 19/24 | 0.86, 0.31 |
| Laya fp32 | ~7.7 | **22/24** | **20/24** | **0.65, 0.83** |
| Laya int8 (per-channel, head in fp32) | **~17.7** | 20/24 | 18/24 | 0.73, 0.75 |

On support tickets the encoder wins every measure. Naive int8 was a bit faster again, but it cost too much agreement:
17/24 and 15/24.

Then I widened the test to 104 requests in five suites: support tickets, product reviews, comment moderation, emails
as structured fields, and chat transcripts as message lists.

| engine (104 requests) | size | yes/no agree | choice agree | score error (0–1) | requests/s |
|---|---|---|---|---|---|
| Laya int8 | **0.5 GB** | **85%** | 66% | **0.19** | **7.2** |
| Laya fp32 | 1.7 GB | **85%** | 63% | **0.19** | 6.1 |
| Qwen2.5-1.5B, 4-bit | 1.8 GB | 80% | **67%** | 0.26 | 0.75 |

Requests/s is JMH on an idle Apple M5 CPU with 3 questions per request, so Laya int8 handles about 22 questions a
second. Upgrading ONNX Runtime from 1.23 to 1.30 alone made Laya fp32 2.2× faster. I also tried Apple's GPU through the
CoreML execution provider. It was 2–3× *slower*: CoreML takes about half the graph in 250-odd pieces, and copying data
back and forth between them costs more than the GPU saves.

The support-ticket lead shrank but didn't disappear. Across the board Laya beats the LLM on yes/no and scores, ties it
on choices, and is about 10× faster. Each has blind spots: Laya agrees with Jev on a chat's "next step" only 25% of the
time (Qwen 70%), and neither does well on whether an email "needs a reply from me".

## Caveats

- A hundred hand-written requests is still a small sample, and 24 was enough to mislead me: the first table above
  looked decisive and wasn't.
- Most numbers in the open-Jev ecosystem are self-reported, and benchmark versions disagree with each other.
- This is API parity, not model parity. You get Jev's contract with a different model behind it.

## Lessons

- **Build the comparison first.** Every turn above was decided by one command against cached real answers, not by
  impressions.
- **On CPU, throughput is FLOPs.** Parallelism rearranges the work; only a smaller model or less input reduces it.
- **Check the tokenizer.** Two silent tokenizer bugs made a working approach look broken. Owning the tokenizer, with a
  test against the reference implementation, turned out cheaper than depending on one.
- **Upgrade the runtime before optimizing.** Bumping ONNX Runtime did more for speed than anything I tried by hand.
- **Benchmark on an idle machine.** Another project's test run in the background made one engine look 3× slower than
  it is.
