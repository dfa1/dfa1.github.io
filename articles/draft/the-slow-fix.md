# The Slow Fix

*2 May 2026*

## Why?

*This is a success story when I was working as a consultant in the early 2010s. Not a triumphant one — more the kind where you keep your head down for two years, make one small improvement at a time, and eventually look up to find the system actually works. The inspiration is "The Phoenix Project"[^phoenix] — minus the novel format. The goal here is simpler: document what happened, in case it's useful to someone standing at the same starting point.*

## Context

The project had been handed over to two developers with some knowledge transfer. The original authors were gone. Senior developers at the company knew about it and kept their distance — the project had a reputation. What remained was 250,000 lines of Java, a 90 MB WAR file — a Java web application packaged for deployment into a servlet container, Apache Tomcat in this case, running a custom-packaged distribution — and a production system going down roughly once a day. Apache Cocoon, Apache Struts, Spring MVC, Hibernate, Drools, jBPM — a decade of late-2000s framework choices stacked on top of each other. Getting it running locally took me a week. The developers had learned, through experience, that the safest move was to touch as little as possible.

The database was MySQL, with a mix of MyISAM and InnoDB tables. MyISAM does not support transactions. Any operation that touched both table types had no atomicity guarantee — a failure mid-write could leave data partially committed with no rollback possible. This had gone unaddressed long enough that compensating logic had accumulated throughout the codebase.

### Custom libraries

The system relied on several in-house libraries with no documentation and no original authors left to ask. The libraries covered areas that standard frameworks already handled — serialization, HTTP communication, data transformation — but with custom behavior that deviated in undocumented ways. Every interaction with them required reverse-engineering from usage in the codebase. We had custom libraries for logging, JDBC utils, a couple of SQL DSLs, XML, and JSON.

### Reflection and proxies

A significant portion of the framework layer was built on Java reflection and dynamic proxies. The code was hard to follow statically and harder to debug at runtime. Proxied objects masked their actual types; reflective calls bypassed IDE navigation and static analysis. Bugs in this layer produced failures with no obvious connection to the triggering code.


### Bad points

The problems weren't just technical. Passwords were stored in plain text, protected by a custom security framework that was itself undocumented. Releases were done manually — deploy, then copy-paste SQL patches directly into the database console, hope nothing breaks, and hope the same patch hadn't already been applied in a previous release. The build process required tribal knowledge that lived only in people's heads, and those people had left. Unit tests existed in the Maven configuration but were disabled. There was no CI, no integration tests, no structured logging — the codebase mixed Logback configuration with a custom `Logger` wrapper class of uncertain provenance, and errors surfaced through `ex.printStackTrace()` or, worse, by email. Empty `catch` blocks were everywhere. Apache Maven and Ant JARs had somehow ended up on the production classpath. Onboarding a new developer meant days of undocumented setup rituals. The team wasn't incompetent — they were paralyzed by a system that punished curiosity.


### Good points

Not everything was broken. The team was already on AWS, which gave us flexibility without needing physical infrastructure. SVN was in use with sane branching defaults — at least history was preserved. There was a backup/restore culture, which meant the database wasn't a gamble. And someone, at some point, had started splitting the monolith into separate modules — incomplete, but a direction worth continuing.


### Team

When I joined the team, there were backend developers and one frontend developer. No QA, no sysadmins.


## Strategic vs. Tactical

The first instinct when inheriting a system like this is to rewrite everything. That instinct is wrong. Joel Spolsky called it "the single worst strategic mistake that any software company can make"[^spolsky] — and the reasoning holds: the old system contains years of accumulated domain knowledge. Bugs that turned into features. Edge cases silently handled. Compensations for upstream failures. Throwing it away means losing all of that, and you won't know what you lost until it's missing in production.


Instead, the approach was cultural before technical. Empower the developers to make changes. Replace fear with a process: if something breaks, understand why, fix it, and share what you learned. Every bug is an opportunity to understand the system better, not a sign that someone should have been more careful.[^motto] Over time, this shift mattered more than any individual refactoring.

On the technical side: fail fast on broken invariants, add post-condition checks, and when in doubt, do less. Complexity was already the enemy — every change that added more of it made the next change harder.

This was evident with the overlapping frameworks and the volume of custom, undocumented internal libraries. At that time I proposed that the team use only open-source libraries with a suitable license — to have documentation, benefit from code already tested by others, and maintain dependencies as a separate concern — but libraries had to be self-contained: no libraries using libraries using libraries.

## Months 0–3: First steps

At this point we had two environments: production and pre-production. Pre-production received fixes with a copy of the previous day's production data — which meant hotfixes and new-feature testing could not happen simultaneously without manually syncing the databases. One EC2 instance for the web app, a primary MySQL instance with daily backups in S3, and a MySQL replica for data-warehouse queries.

The first concrete change was removing Struts and consolidating on Spring MVC. This was already underway before I joined the team and it was in good shape. Not because Struts was the biggest problem — it wasn't — but because having two web frameworks in the same application was unnecessary complexity with no payoff. Incremental changes from the start: no big-bang refactorings, no feature freezes. The system stayed in production throughout: but like a bonsai, a small cut there, another small one there, and stopping for a while.

## Months 4–6

With a baseline of instability, we started clearing the underbrush. Unused classes, JAR conflicts, disabled tests. The JAR hell was particularly bad — Apache Maven and Ant artifacts on the production classpath, multiple copies of the same dependency under different group IDs, version conflicts surfacing as runtime errors with no clear cause. We untangled it incrementally, release by release, asking always: "Why do we need to keep this dependency?"

Logging moved from `printStackTrace` and email alerts to SLF4J with Logback: this was mostly grep-and-replace work across the codebase. Empty `catch` blocks were everywhere: many silently ignored exceptions, with extra code paths added throughout to compensate.

The build became a single command: `mvn package`, with unit tests re-enabled to cover the new parts of the system.

The data-warehouse job, which ran weekly and reliably died with out-of-memory errors, got its first real attention. It had been built to simulate materialized views in MySQL: the primary node accepted OLTP reads and writes, while a replica received WAL changes and served read-only queries. Hibernate, combined with some reflection hacks and connection-pooling issues, made it unstable — it frequently failed, and we had to manually restart it the following day.

The fix was simple: track which entities changed during the OLTP workload in a separate table, then process those changes asynchronously with eventual consistency. Instead of refreshing all entities at midnight, the system updated only those that had actually changed — an incremental materialized view. This made the process reliable and kept warehouse data current. It also freed the team to focus on other problems.

Other minor (as effort) but important fixes:
- improved backup/restore tooling — a single script using SSH tunnel to avoid intermediate copies; this was the *same* script used to restore the database in AWS;
- the collation of all tables to utf8 at the same time
- addressing some thread safety issues by protecting shared mutable parts with `synchronized` blocks: it was causing random bugs in production and it was easy enough to fix;


## Months 7-9

Infrastructure started getting attention. We introduced Puppet for AWS EC2 provisioning and switched to RPM builds via a Maven plugin, replacing the manual deployment rituals with something repeatable. We could create a new environment with a single `puppet apply`, pulling all dependencies — JRE, Apache Tomcat, sshd with our SSH public keys, and all changes in `/etc`. It was a good moment for the team: everyone became a sysops. We also started deploying smaller releases more often — instead of once every three months, we shipped regularly.

A UAT (User Acceptance Test) environment came online — for the first time, there was a place to verify changes before they hit production.

Drools, a rules engine used for a small part of the business logic, was removed and replaced with Java validation logic. It was an experiment that had reached production but was pulling a large number of extra JARs — Eclipse JDT, ANTLR, ASM, protobuf, xstream, commons-\* and more. It had likely been intended for broader use, but the team had no need for it beyond the narrow case it was already handling.

We also started forward-compatibility work for Java 8, already mainstream elsewhere but not yet adopted here. The migration ran in two phases: first, use Java 8 as the compiler target; then migrate the codebase to embrace new language features — notably lambdas and the new date/time API (the codebase still used `Calendar` and `Date`). The second phase ran in parallel with other ongoing work.

The application produced documents for Word/Excel using OpenOffice and ODT templates: the OpenOffice process was unstable due to memory leaks inside the process itself, which couldn't be fixed directly. What worked was:
- restarting the service daily (the leak was small — a few KB per invocation)
- but the real fix was starting OpenOffice *per request*: slower per request but far more stable operationally.

The milestone that stood out most: the team started treating bugs as opportunities to understand the system rather than fires to extinguish. The rule was: bug → failing test case → fix → deploy. We started modifying core parts of the system with less fear. The boy scout rule — leave the code better than you found it — had become a team habit.

## Months 10-12

We started to run.

Continuous integration arrived, along with a migration from SVN to Git. Both changed how the team worked more than any code change had. Jenkins meant every commit was verified automatically; Git meant branching was cheap enough to actually use. To enable continuous integration, we started deploying the master branch to UAT daily — possible thanks to Jenkins, RPMs, and Puppet.
At this point it was possible to run a complete build without any custom library (previously we had forked versions of some libraries deployed locally) — this taught me to always try to collaborate upstream to fix the issue; forking is not a viable solution.

Flyway was introduced to manage database migrations — before this, schema changes were applied by hand with no versioning, which meant different environments could silently diverge. MyISAM tables in MySQL were converted to InnoDB, restoring transactional guarantees that had been missing. This allowed us to catch database migration issues early.

I remember setting up a Jenkins job to gamify the Java 8 migration: every morning we tracked how many files remained to migrate. We dropped a custom library that emulated lambdas in Java using reflection and bytecode rewriting — essentially a home-grown version of [Lambdaj](https://code.google.com/archive/p/lambdaj/) — and all uses of the Joda-Time library.

One day we deployed JDK 8 to production with no vestigial dependency on `Calendar`, Joda-Time, or the custom lambda library.

All new code required unit tests — not as a rule handed down, but as a shared expectation the team had started to own.

We introduced Sonar and IntelliJ IDEA inspections for static analysis; the number of subtle bugs they surfaced was striking. JavaMelody went in for runtime monitoring.

Stateful DAOs — a pattern that had caused unit-of-work problems throughout the codebase — were systematically removed. The DAOs were duplicating what the Hibernate `Session` already provides: identity map, dirty tracking, first-level cache. Except they did it with bugs. The fix was to delete the custom state management and rely on the `Session` directly.

At this point, the database was still holding passwords in cleartext — we discussed using Apache Shiro internally but ultimately settled on Spring Security (the project was already using Spring and most developers were comfortable with it). A few hours later, passwords were stored as BCrypt[^bcrypt] with a lazy migration pattern: the system could read both formats, and as users authenticated, their passwords were migrated on the fly.

Another important point was publishing AWS metrics on a dashboard to check various parts of the system, using some Python scripts and AWS CloudWatch.



## Months 13-18

The infrastructure side caught up with the application side. CentOS 7 replaced CentOS 6; `systemctl` replaced the handwritten bash scripts managing services. We switched to the upstream Apache Tomcat 7 package, dropping the custom distribution we had inherited.

Hibernate was upgraded from 3.2 to 5. The migration was tricky: the codebase had custom code hooking into Hibernate callbacks to handle dynamic proxies and entitlements, and JAR conflicts kept the upgrade stuck. We moved incrementally: 3.2 → 3.3 → 3.4 → latest 3.x → 4.x → 5. At the end, the code around that layer was cleaner and more explicit than anything it replaced — a good case for bottom-up design.

Since the system was growing in usage, we added a second application node to share the load. Puppet made this straightforward. Hazelcast came in for distributed locking across the cluster and as a second-level cache for Hibernate.

```
                       ┌─────────────────┐
                       │  Apache HTTPD   │
                       │  (load balancer)│
                       └────────┬────────┘
                                │
                   ┌────────────┴────────────┐
                   │                         │
            ┌──────▼──────┐           ┌──────▼──────┐
            │  App Node 1 │           │  App Node 2 │
            │   Tomcat    ├───────────┤   Tomcat    │
            │             │ Hazelcast │             │
            │  · L2 cache │  cluster  │  · L2 cache │
            │  · dist lock│           │  · dist lock│
            │  · sessions │           │  · sessions │
            └──────┬──────┘           └──────┬──────┘
                   │                         │
                   └───────────┬─────────────┘
                               │
                  ┌────────────┴────────────┐
                  │                         │
           ┌──────▼──────┐          ┌───────▼──────┐
           │   MySQL     │          │    MySQL      │
           │   Primary   │──WAL────►│   Replica     │
           │  (R/W OLTP) │          │  (read-only)  │
           └─────────────┘          └──────────────┘
```

Around this time, the second frontend developer left — taking with him some knowledge about a custom build solution for minifying JS and CSS files. The process had caused problems in the past: it was a manual step that had to be triggered every time a JS or CSS file changed, and it reliably broke in UAT even when it worked locally. I proposed switching to [wro4j](https://github.com/wro4j/wro4j) to build minified assets automatically during the Maven build, making it impossible to forget.


## Months 19-24

The continuous-improvement cycle was fully in place.

Integration tests and automated acceptance tests replaced a purely manual QA process. jBPM, the workflow engine, was removed — its use case didn't require it. A straightforward state machine, written from scratch and fully covered by integration tests, replaced it with a fraction of the complexity.

Let's Encrypt certificates replaced manual certificate management, which had been adding overhead as the number of environments grew — by this point we had production, pre-production, UAT, and CI.

Load testing ran for the first time, giving numbers instead of guesses when discussing performance. We used JMeter to simulate load and surface issues.

At the time we completed the "Excel over HTTP" integration: the Apache POI version was holding the entire Excel file in memory, causing out-of-memory errors. After an internal discussion, I proposed avoiding Apache POI for this part and directly manipulating the XML inside the file (XLSX is a ZIP archive of XML files, so direct manipulation was straightforward) — this was faster and consumed less memory. The trade-off was another small internal library, but this time it was clearly documented and covered by unit and integration tests.

By this point the system looked almost nothing like what we had inherited. The WAR file was down to 60 MB. The codebase was at 180,000 lines — 70,000 fewer than when we started, despite three years of new features. There were over 200 database migrations in Flyway, every schema change tracked and repeatable. Production outages had been absent for months.

We were deploying several times a week — sometimes twice in a day.

## Month 24+

After two years, I left the company.

The team had grown to 5 backend developers, 2 frontend developers, and 2 QAs — still no sysadmins.

The team was in good shape: confident, autonomous, and shipping regularly. The remaining stretch had been quieter:
- blob-handling cleanups (moved outside database to S3);
- several new integrations with external services (SOAP and REST);
- infrastructure scaled to 24 machines across 3 clusters with MySQL primary/replica;
- and the first zero-downtime production deployment;
- Work toward Java 9+ compatibility was also underway.

What I found on day one and what I left behind were barely recognizable as the same system.

This wasn't my first lead role. But in retrospect, it was one of the most satisfying — not because of the technical work, but because of watching the junior developers grow.
They went from being afraid to touch anything to taking ownership of the system, making decisions independently, and treating problems as something to solve rather than something to survive.


## By the Numbers

|                  | Start          | End                          |
|------------------|----------------|------------------------------|
| Team             | 2 backend, 1 frontend | 5 backend, 2 frontend, 2 QA |
| Production outages | ~1/day       | 0 for months                 |
| Deploy frequency | ~1/quarter     | Several/week                 |
| Codebase         | 250,000 LOC    | 180,000 LOC                  |
| WAR size         | 90 MB          | 60 MB                        |
| DB migrations    | 0 tracked      | 200+ in Flyway               |
| Environments     | 2 (pre-prod, production) | 4 (CI, UAT, pre-prod, production) |

## Tactical and Strategic

Tactical fixes buy time and reduce pain immediately. Strategic bets change the system's trajectory — they compound over time and enable things that were previously impossible.

The key distinction: tactical work has an immediate, visible payoff. Strategic work often looks like overhead until suddenly it doesn't.

The clearest examples from this project:

- **Fixing daily outages** was the obvious urgent priority. The real strategic win was that the team stopped being afraid to touch the system.
- **A single `mvn package` command** was a convenience fix. Six months later, it made CI possible.
- **A UAT environment** looked like a nice-to-have. It was the precondition for deploying multiple times per week.
- **Flyway migrations** eliminated an operational annoyance. They also made database divergence between environments structurally impossible.
- **Puppet provisioning** seemed like infrastructure housekeeping. When we needed to scale to 24 machines, it was a non-event.

There's a useful negative example too. At some point the conversation about migrating to PostgreSQL came up — the manager pushed for it. The call was to defer it. Not because it was wrong in principle, but because the preconditions weren't there: no reproducible migrations, no stable environments, no test coverage to verify behavior across a different database. It would have been high-cost, high-risk work with questionable strategic payoff given where the system was. Sequencing matters as much as choosing.

The judgment isn't "fix the most painful thing." It's "fix the thing that opens the next door."

## Closing

### Bad patterns

These appeared constantly across the codebase and are worth naming explicitly, because they recur
in most legacy codebases:

**Log and rethrow** — catching an exception, logging it, and re-throwing it produces duplicate stack traces and obscures the origin of the error. Either handle it or let it propagate.

**Swallowed exceptions** — empty `catch` blocks, or catches that only log a message without preserving the exception, hide failures silently. Sonar was particularly effective at surfacing these.

**Resource leaks** — streams, connections, and other resources not closed in `finally` blocks or try-with-resources. Java 7's try-with-resources statement exists precisely for this.

**Unnecessary complexity** — overly generic solutions, deep inheritance hierarchies, abuse of reflection and proxies. Simple code that is easy to read and verify is worth more than clever code that is hard to debug. Use big frameworks like Drools or jBPM only where a simple implementation won't suffice.

**Useless tests** — tests that only verify happy paths, or that duplicate implementation rather than testing behavior. Mutation testing is a useful tool for finding them.


### Recommended techniques

These are the practices that moved the needle most over three years:

**Bugs as opportunities** — reframe team culture: no drama; fix, learn, share, and move on.

**Incremental refactoring** — no big-bang rewrites. Every change ships to production. If a refactoring is too large to merge incrementally, split it.

**Test before replace** — before removing or replacing a component, write tests that capture its behavior. The tests become the specification for the replacement.

**Segregate subsystems** — keep transactions, logging, retry logic, and background jobs independent. Circular dependencies between subsystems make changes expensive.

**Minimal design** — throw away what isn't needed. The best code is the code that doesn't exist.

**Mutation testing** — use it to find tests that don't actually verify anything.

**Automate deploy and provision** — manual steps are where environments diverge and where outages start.

**Deploy checklist always up to date** — treat infrastructure as code and tests as production code. They deliver value and deserve the same attention and engineering effort: everything matters for delivering value to customers.

**Automate** — database migrations, infrastructure provisioning, load tests.

**Read books:** Michael Feathers, *Working Effectively with Legacy Code* — the practical manual for everything described here. If you're inheriting a large codebase without tests, start there.

**Find your motto** — a guiding principle shapes how the team approaches the system. A good example[^picard]: "Make it so every subsystem can be found and repaired manually, even if you need to crawl to reach it."

---

[^phoenix]: Gene Kim, Kevin Behr, George Spafford — *The Phoenix Project* (2013).

[^spolsky]: Joel Spolsky, [*Things You Should Never Do, Part I*](https://www.joelonsoftware.com/2000/04/06/things-you-should-never-do-part-i/) (2000).

[^motto]: "Every bug is an opportunity" — a personal motto that stuck. Developers who have worked with me since will recognize it. The phrase does real work: it reframes a problem as a chance to learn, and that shift in mindset changes how a team responds under pressure.

[^picard]: Picard Engineering Tips (@PicardTips on X) — fictional engineering advice in the voice of Jean-Luc Picard. https://x.com/PicardTips

[^bcrypt]: Spring Security `BCrypt` — https://docs.spring.io/spring-security/site/docs/current/api/org/springframework/security/crypto/bcrypt/BCrypt.html
