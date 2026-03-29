---
title: "The Joy of Proper Encapsulation"
author: Davide Angelocola
date: 2026-03-27
---

# The Joy of Proper Encapsulation

Today I pushed a commit to [hosh](https://github.com/hosh-shell/hosh). The [commit](https://github.com/hosh-shell/hosh/commit/7d51c4838faeb3ce42486e374c1eb45f395a5a49) message was innocent enough: *“fix: avoid usage of jdk.internal.Signal”*. The diff was 20 lines added, 31 removed.
And it was wrong — not syntactically, but semantically. It changed the behavior of my program in a way I didn’t fully appreciate until I sat down and thought about what the module system was actually telling me.

This is a story about that mistake, and about why I think Java’s module system, for all the grief it gets, is one of the best things that happened to the platform.

## The problem

Hosh is an interactive shell. When you press `Ctrl+C`, I want the current pipeline to stop, not the whole JVM. This is a fundamental requirement: cancel the running command, return to the prompt.

In POSIX terms, I need to handle SIGINT without terminating the process.

For years, the code looked something like this:

```java
import jdk.internal.misc.Signal;

private static final Signal INT = new Signal("INT");

private void cancelFuturesOnSigint() {
    Signal.handle(INT, signal -> {
        for (Future<ExitStatus> future : futures) {
            future.cancel(true);
        }
    });
}
```

This worked perfectly. It had exactly the semantics I needed: intercept the signal, cancel running work, keep the JVM alive. There was just one problem — `jdk.internal.misc.Signal` is an internal API. My `module-info.java` needed this in the build:

```xml
<arg>--add-exports</arg>
<arg>java.base/jdk.internal.misc=hosh.runtime</arg>
```

I had even left a `@Todo` annotation on the field: *“this usage of internal API cannot be avoided as far as I know.”*

## The “fix” that wasn’t

With JDK 25 tightening encapsulation further, I decided to bite the bullet and remove the internal API usage. I replaced the signal handler with `Runtime.addShutdownHook`:

```java
this.shutdownHook = Thread.ofVirtual().unstarted(() -> {
    for (Future<ExitStatus> future : futures) {
        future.cancel(true);
    }
});
Runtime.getRuntime().addShutdownHook(this.shutdownHook);
```

Clean. No internal APIs. No `--add-exports`. The module system was happy.

But the semantics were completely different.

A shutdown hook runs when the JVM is *shutting down*. That means Ctrl+C now kills the entire process. For a web server, that might be fine — you’re probably done anyway. For a shell, it’s a disaster. The whole point is to survive SIGINT and keep running.

The module system wasn’t just being pedantic when it hid `jdk.internal.misc.Signal` behind a wall. It was telling me something: *this API is not for you, and if you use it, you’re taking on risk that the semantics might change or disappear.* The problem was that I heard the message but didn’t have a good answer for it.

## The landscape of non-answers

Let’s walk through every option available in 2026 for handling SIGINT in Java without terminating the JVM.

**`jdk.internal.misc.Signal`** — the API I was using. Requires `--add-exports` to break into `java.base` internals. JDK 25 makes this harder. JDK 26 will likely make it harder still. Not a long-term option.

**`sun.misc.Signal`** — the older version of the same API. Moved to the `jdk.unsupported` module in JDK 9. Here’s the thing: despite the scary name, `jdk.unsupported` is a *real module* that you can declare a dependency on. You add `requires jdk.unsupported;` to your `module-info.java` and you’re done. No `--add-exports` needed. JEP 260 explicitly classified `sun.misc.Signal` as a *critical internal API* that would remain accessible precisely because there is no replacement.

**`Runtime.addShutdownHook`** — different semantics entirely. Only fires during JVM shutdown. Not suitable for interactive applications that need to survive signals.

**JNR-Signal** — a small library that wraps native signal handling via JNR-FFI. Adds a dependency, brings in native code, and the project has 8 stars on [GitHub](https://github.com/jnr/jnr-signal). Not exactly battle-tested.

**Panama FFM** — you could register a POSIX `sigaction` handler through the Foreign Function & Memory API. Technically pure public API, but the amount of ceremony required to handle a two-letter signal is absurd. And you’d be reimplementing what the JVM already does internally.


## What the module system got right

Here’s where my perspective might be unpopular: I think the module system is doing exactly what it should.Before modules, the boundary between “public API” and “implementation detail” was a gentleman’s agreement. You *could* import `sun.misc.Signal`. The compiler would warn you. Experienced developers would tell you not to. But nothing stopped you, and over time the entire ecosystem became dependent on internal APIs that were never designed to be stable.

The module system turned a social contract into an enforced one. When I had `--add-exports java.base/jdk.internal.misc=hosh.runtime` in my build, that flag was a declaration of technical debt. It was visible, searchable, and ugly on purpose (I think). Every time I looked at it, I knew I was doing something I shouldn’t.

Compare that to the pre-module world where the same dependency would hide in a regular import statement, indistinguishable from any other.

This is the joy of proper encapsulation: *it makes the wrong thing look wrong*. Not wrong at runtime, not wrong in a code review comment that gets ignored — wrong in the structure of the build itself.

## The right fix

[The final fix](https://github.com/hosh-shell/hosh/commit/40890e0d984b4f6a8f986a20c9f9a956f5ad1fd8) is just delegating the hard work to [JLine](https://jline.org/versions/4.0/).
No `--add-exports`. No broken semantics.
Crucially, JLine is already a dependency of hosh, it is well-maintained, and they recently released a [FFM-based terminal](https://github.com/jline/jline3/tree/master/terminal-ffm) that drops the JNA and JAnsi backends entirely — with proper module exports for all modules.


The correct answer for hosh, today, is to use `JLine` in the `Supervisor` class:

```java
class Supervisor implements AutoCloseable {

	private static final Logger LOGGER = LoggerFactory.forEnclosingClass();

	private Terminal terminal;
	private Terminal.SignalHandler previousSigintHandler;

	// With JLine-based SIGINT interception — used in Interpreter for top-level command execution.
	public Supervisor(Terminal terminal) {
		this.terminal = terminal;
	}

    private void installSigintHandler() {
		if (terminal != null) {
			LOGGER.fine("register INT signal handler");
			previousSigintHandler = terminal.handle(Terminal.Signal.INT, signal -> {
				LOGGER.info("SIGINT received, cancelling futures...");
				for (Future<ExitStatus> future : futures) {
					LOGGER.finer(() -> String.format("cancelling future %s", future));
					future.cancel(true);
				}
			});
		}
	}

    private void restoreDefaultSigintHandler() {
		if (terminal != null && previousSigintHandler != null) {
			LOGGER.fine("restoring default INT signal handler");
			terminal.handle(Terminal.Signal.INT, previousSigintHandler);
			previousSigintHandler = null;
		}
	}
```

You might wonder: if `sun.misc.Signal` is officially supported via `jdk.unsupported`, why not just use that? The answer is that hosh already depends on JLine for terminal handling, and JLine wraps signal management as a first-class concern. Delegating to it means one less direct dependency on JDK internals — even blessed ones — and the signal handling composes naturally with the rest of the terminal lifecycle.

See the [JEP 260](https://openjdk.org/jeps/260) for more information.

## The lesson

The module system didn’t create this problem. What the module system did was make the problem *visible*. It forced me to confront the fact that I was depending on an implementation detail, and it made that dependency explicit.

When I tried to “fix” it by switching to shutdown hooks, I was optimizing for the wrong thing. I was making the module system happy at the cost of correctness. The module boundary was a signal (*pun intended*) that I should have listened to more carefully.

Good encapsulation doesn’t just protect you from other people’s implementation details. It protects you from your own wishful thinking.

## And another small joy...

While enforcing the zero-warnings policy, another **opportunity** appeared: javac emitted this when processing `Compiler.java`:

```
interface org.antlr.v4.runtime.tree.ParseTree in module org.antlr.antlr4.runtime
is not indirectly exported using 'requires transitive'
```

The root cause was subtle: `InternalBug`, a private exception class inside `Compiler`,
accepted a `ParseTree` in its constructor just to call `.getText()` on it. This exposed an
ANTLR type at the constructor signature level — forcing the module system to consider
`ParseTree` part of the visible API, even though it was never meant to be.


The fix was simple:

```java
// Before
throw new InternalBug(ctx);           // ctx is a ParseTree
```

```java
// After
throw new InternalBug(ctx.getText()); // plain String — no ANTLR leakage
```

This eliminated the `ParseTree` import entirely. The `InternalBug` constructor now takes a
`String`, keeping ANTLR as a true implementation detail of `hosh.runtime` with no surface
area leaking outward.

This is a small change (10 lines net), but it's a good example of how JPMS surfaces
coupling that would otherwise be invisible in a classpath-based build. In case you're interested, [this is the commit](https://github.com/hosh-shell/hosh/commit/513a9219f7298189d243333e3556c3e0620b95ae).

