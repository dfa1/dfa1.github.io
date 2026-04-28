# out the tar pit

TODO: rework the title, this is the original title by 2017 in Notes.app

## why?

This is a success story. Not a triumphant one — more the kind where you keep your head down for two years, make one small improvement at a time, and eventually look up to find the system actually works. The inspiration is *The Phoenix Project*[^phoenix] — minus the novel format. The goal here is simpler: document what happened, in case it's useful to someone standing at the same starting point.

- to document a success story (never lose the hope and never give up)
- inspiration is the book https://www.amazon.com/Phoenix-Project-DevOps-Helping-Business/dp/0988262592
  (minus the novel part)
- celebrate an awesome team

[^phoenix]: Gene Kim, Kevin Behr, George Spafford — *The Phoenix Project* (2013).


## context

The project had been handed over to two developers with some knowledge transfer. The original authors were gone. Senior developers at the company knew about it and kept their distance — the project had a reputation. What remained was 250,000 lines of Java, a 90 MB WAR file — a Java web application packaged for deployment into a servlet container, Apache Tomcat in this case, running a custom-packaged distribution — and a production system going down roughly once a day. Apache Cocoon, Apache Struts, Spring MVC, Hibernate 3.2, Drools, jBPM — a decade of framework choices stacked on top of each other. Getting it running locally took me a week. The developers had learned, through experience, that the safest move was to touch as little as possible.

The database was MySQL, with a mix of MyISAM and InnoDB tables. MyISAM does not support transactions. Any operation that touched both table types had no atomicity guarantee — a failure mid-write could leave data partially committed with no rollback possible. This had gone unaddressed long enough that compensating logic had accumulated throughout the codebase.

### Custom libraries

The system relied on several in-house libraries with no documentation and no original authors left to ask. The libraries covered areas that standard frameworks already handled — serialization, HTTP communication, data transformation — but with custom behavior that deviated in undocumented ways. Every interaction with them required reverse-engineering from usage in the codebase.
We had custom libraries for logging, JDBC utils, a couple of SQL DSL, XML and JSON.

### Reflection and proxies

A significant portion of the framework layer was built on Java reflection and dynamic proxies. The code was hard to follow statically and harder to debug at runtime. Proxied objects masked their actual types; reflective calls bypassed IDE navigation and static analysis. Bugs in this layer produced failures with no obvious connection to the triggering code.


### Bad points

The problems weren't just technical. Passwords were stored in plain text. Releases were done manually — deploy, then copy-paste SQL patches directly into the database console, hope nothing breaks, and hope the same patch hadn't already been applied in a previous release. The build process required tribal knowledge that lived only in people's heads, and those people had left. Unit tests existed in the Maven configuration but were disabled. There was no CI, no integration tests, no structured logging — the codebase mixed Logback configuration with a custom `Logger` wrapper class of uncertain provenance, and errors surfaced through `ex.printStackTrace()` or, worse, by email. Empty `catch` blocks were everywhere. Apache Maven and Ant JARs had somehow ended up on the production classpath. Onboarding a new developer meant days of undocumented setup rituals. The team wasn't incompetent — they were paralyzed by a system that punished curiosity.

- manual release + deploy + manual patch db
- custom security framework
- custom libraries for everything without documentation and original developers out
- poor security practices (plain text passwords)
- custom packages for java and tomcat
- unit tests disabled in maven
- no integration tests
- no continuous integration
- logging via email / ex.printStackTrace()
- many empty try catch blocks
- jar hell (apache maven / apache ant in the production classpath of a webapp)
- frequent outages in production (once per day)
- build from scratch requires obscure voodoo practices
- team fears changes


### Good points

Not everything was broken. The team was already on AWS, which gave us flexibility without needing physical infrastructure. SVN was in use with sane branching defaults — at least history was preserved. There was a backup/restore culture, which meant the database wasn't a gamble. And someone, at some point, had started splitting the monolith into separate modules — incomplete, but a direction worth continuing.


### Team

When I joined that team, it was backend developers and one frontend developer. No QA, no sysadmins.


# Strategic vs Tactical

The first instinct when inheriting a system like this is to rewrite everything. That instinct is wrong. Joel Spolsky called it "the single worst strategic mistake that any software company can make"[^spolsky] — and the reasoning holds: the old system contains years of accumulated domain knowledge. Bugs that turned into features. Edge cases silently handled. Compensations for upstream failures. Throwing it away means losing all of that, and you won't know what you lost until it's missing in production.

[^spolsky]: Joel Spolsky, [*Things You Should Never Do, Part I*](https://www.joelonsoftware.com/2000/04/06/things-you-should-never-do-part-i/) (2000).

[^motto]: "Every bug is an opportunity" — a personal motto that stuck. Developers who have worked with me since will recognize it. The phrase does real work: it reframes a problem as a chance to learn, and that shift in mindset changes how a team responds under pressure.

Instead, the approach was cultural before technical. Empower the developers to make changes. Replace fear with a process: if something breaks, understand why, fix it, and share what you learned. Every bug is an opportunity to understand the system better, not a sign that someone should have been more careful.[^motto] Over time, this shift mattered more than any individual refactoring.

On the technical side: fail fast on broken invariants, add post-condition checks, and when in doubt, do less. Complexity was already the enemy — every change that added more of it made the next change harder.

This was evident with the choices of overlapping frameworks + amount of custom internal, undocumneted
libraries. At that time I proposed to the team to use always open source libraries with a suitable
license in order to:
- have some documentation
- have the code already tested by someone else
- maintain it as separate process
- but libraries must be self-contained: no libraries, using libraries using libraries.

months 0–6: first steps
---

The first concrete change was removing Struts and consolidating on Spring MVC. This was already started before I joined the team and it was in good shape. Not because Struts was the biggest problem — it wasn't — but because having two web frameworks in the same application was unnecessary complexity with no payoff. Incremental changes from the start: no big-bang refactorings, no feature freezes. The system stayed in production throughout: but like a Bonsai, a small cut there another small there and stop for a while.

At this point we had one environment: production.
We had one EC2 instance for the WebApp.
Then a primary MySQL, with daily backups in S3 and a MySQL replica for datawarehouse queries.


months 6–12
---

With a baseline of instability, we started clearing the underbrush. Unused classes, JAR conflicts, disabled tests. The JAR hell was particularly bad — Apache Maven and Ant artifacts on the production classpath, multiple copies of the same dependency under different group IDs, version conflicts surfacing as runtime errors with no clear cause. We untangled it incrementally, release by release asking always "Why we need to keep this dependency?".

Logging moved from `printStackTrace` and email alerts to SLF4J with Logback: this was a bit of grep/sed work across all the files. Our enemy was "empty catch blocks": a lot of ignored exceptions causing
other issues and a lot of extra code paths in attempt to repair the damage.

The build became a single command: `mvn package`, with unit tests cleared and enabled back to cover
the new parts of the system.

The data warehouse job, which ran weekly and reliably died with out-of-memory errors, got its first attention. It was build with care to simulate materialized views in MySQL: on the mysql primary node
was acceptcing write and reads for OTLP transaction and another mysql was receiving WAL changes were clients were connecting to it in readonly.
The usage of Hibernate, with some reflection hacks and some connection pooling issue made it unstable (often it was failing, and we to manually restart it on the day after).
The fix was simple: saving the list of entities changed in the OTLP workload in a separate table
and then have a thread running those with eventual consistency. So intead of having all entities refreshed at midnight, the system was changing only the one with actual changes (like an incremental materialized view). This made the process reliable and DWH data was always "fresh"
It is important to note that this freed the team a bit to give some focus of the team to tackle other issues.

months 12–18
--

Infrastructure started getting attention. We introduced Puppet for AWS EC2 provisioning and switched to RPM builds via a Maven plugin, replacing the manual deployment rituals with something repeatable.
In this way we could create a new environment with a single `puppet apply`, pulling all depedencies
like JRE, apache tomcat, sshd with our SSH public keys and all the changes in /etc. It was really
good moment for the team: everyone became a sysops. We also started to deploy smaller releases in PROD: instead of once every 3 months, we started to deploy more often.

A UAT (User Acceptance Test)  environment came online — for the first time, there was a place to verify changes before they hit production.


Drools, a rules engine that had been used for a small part of the business logic, was removed and replaced with Java validation logic. It was an experiment that went to production but it was pulling
a lot of extra JARs (i.e. things like Eclipse JDT, ANTLR, ASM, protobuf, xstream, commons-\*, and more). Most likely it was meant to be used elsewhere but we had

We also started forward-compatibility work for Java 8, already mainstream elsewhere but not yet adopted here.

The migration was in 2 phases:
- first use Java 8 as package
- then start to migrate Java code to embrace new features of Java8, notably lambda and datetime libraries (at the time, Java had only `Calendar` and `Date`).

The second point was project to complete in parallel with other activities.

The milestone that stood out most: the team started treating bugs as opportunities to understand the system rather than fires to extinguish. The rule was "bug -> failing test case -> fix" and deploy.
Suddenly, we started to modify core parts of the system and with less fear.
The boy scout rule — leave the code better than you found it — had become a team habit.

months 18–24
--

We started to run.

Continuous integration arrived, along with a migration from SVN to Git. Both changed how the team worked more than any code change had. Jenkins meant every commit was verified automatically; Git meant branching was cheap enough to actually use.

To enable continuous integration, we started to deploy master branch daily in the new UAT. This
was possible by using Jenkins, RPMs and Puppet.

Flyway was introduced to manage database migrations — before this, schema changes were applied by hand with no versioning, which meant different environments could silently diverge. MyISAM tables in MySQL were converted to InnoDB, restoring transactional guarantees that had been missing. The data warehouse got a MySQL read replica for eventually-consistent reporting.
This enabled to catch early on database migration issues.


I remember setting up a Jenkins job to "gamify" the migration to Java8: every morning we were checking how many
files we were missing to migrate! We dropped a custom build library to emulate lambda in Java
(similar to https://github.com/dfa1/lambdascript but for Java) and all use or Jodatime library (TODO: add link).

One day we deployed JDK8 in production without any vestigial dependency to Calendar/Jodatime/custom lambda library (that was using reflection and bytecode rewriting).

All new code required unit tests — not as a rule handed down, but as a shared expectation the team had started to own.

We introduced Sonar and IntelliJ IDEA inspections for static analysis; the number of subtle bugs they surfaced was striking. JavaMelody went in for runtime monitoring.

Stateful DAOs — a pattern that had caused unit-of-work problems throughout the codebase — were systematically removed. TODO: explain this better.

## months 24–30

The infrastructure side caught up with the application side. CentOS 7 replaced the Centos 6; `systemctl` replaced the handwritten bash scripts managing services.

We started to apache tomcat 7 upstream package, not a custom one.

Hibernate upgraded from 3.2 to 5. This was really trick migration for us as Hibernate was stuck in
the past because of a lot of JAR issues => it took time. We did the migration in stesp: 3.2 -> 3.3 ->  3.4 -> latest 3.x then 4.x and finally Hibernate 5. It was super tricky because we had custom code
hooking in the Hiberante callbacks to do special stuff with dynamic proxies and entilements.
At the end of the migration, we had really nice and elegant code around that... a good case for bottom up design.

Since the system was used by more and more users, we added a second application node to share the load: it was easy to setup all the requires config with puppet.

Hazelcast came in for distributed locking across the cluster and to have a second level cache for hibernate.

At the time, we had a second frontend developer leaving. With him also some knownledge about a custom build solution to "minify" the JS and CSS files. This caused multiple times issues in the past because it was a manual process to trigger every time one JS/CSS was changed as part of a file. Locally it was working but not in the UAT env. One day, I proposed to use a wro4j (TODO: link) to automatically
build the minified JS/CSS files during the build process (so impossible to forget).


## months 30–36

The continuous improvement kaizen was finally fully operative.

Integration tests and automated acceptance tests replaced a purely manual QA process. jBPM, the workflow engine, was removed — its use case didn't require it. A straightforward state machine, written from scratch and fully covered by integration tests, replaced it with a fraction of the complexity.

Let's Encrypt certificates replaced the manual certificate management, that was causing extra work
on a growing number of environments (at this time we had production, pre-preproduction, UAT and CI).

Load testing ran for the first time, giving numbers instead of guesses when discussing performance.
We used JMeter to simulate load and spot issues.

By this point the system looked almost nothing like what we had inherited. The WAR file was down to 60 MB. The codebase was at 180,000 lines — 70,000 fewer than when we started, despite three years of new features. There were over 200 database migrations in Flyway, every schema change tracked and repeatable. Production outages had been absent for months.

We were deploying several times a week, someone even twice per day.

## month 36+

After three years, I left the company. The team had grown to five backend developers, two frontend, and two QA — still no sysadmins,

The team was in good shape: confident, autonomous, and shipping regularly.

What I found on day one and what I left behind were barely recognizable as the same system.
Twenty-four machines across three clusters, two master/slave replicas — still no sysadmin. The last stretch was quieter: internal cleanups, a couple new external integrations, the first zero-downtime production deployment.

This wasn't my first lead role. But in retrospect it was one of the most satisfying — not because of the technical work, but because of watching the junior developers grow. They went from being afraid to touch anything to taking ownership of the system, making decisions independently, and treating problems as something to solve rather than something to survive.

- several integrations with external services Soap/rest/excel over http :)
- many internal cleanups (e.g. blob handling)
- new integration with external system
- deploy in production without downtime
- preparing to java9


## Closing

### Bad patterns

These appeared constantly across the codebase and are worth naming explicitly, because they recur in most legacy codebases:

**Log and rethrow** — catching an exception, logging it, and re-throwing it produces duplicate stack traces and obscures the origin of the error. Either handle it or let it propagate.

**Swallowed exceptions** — empty `catch` blocks, or catches that only log a message without preserving the exception, hide failures silently. Sonar was particularly effective at surfacing these.

**Resource leaks** — streams, connections, and other resources not closed in `finally` blocks or try-with-resources. Java 7's try-with-resources statement exists precisely for this.

**Unnecessary complexity** — overly generic solutions, deep inheritance hierarchies, abuse of reflection and proxies. Simple code that is easy to read and verify is worth more than clever code that is hard to debug. Use big frameworks like `Drools` or `jBPM` where a simple code could do it.

**Useless tests** — tests that only verify happy paths, or that duplicate implementation rather than testing behavior. Mutation testing is a useful tool for finding them.


### Recommended techniques

These are the practices that moved the needle most over two years:

**Bugs as opportunities** reframe team culture to be more positive, no drama and fix, learn, share and move on.

**Incremental refactoring** — no big-bang rewrites. Every change ships to production. If a refactoring is too large to merge incrementally, split it.

**Test before replace** — before removing or replacing a component, write tests that capture its behavior. The tests become the specification for the replacement.

**Segregate subsystems** — keep transactions, logging, retry logic, and background jobs independent. Circular dependencies between subsystems make changes expensive.

**Minimal design** — throw away what isn't needed. The best code is the code that doesn't exist.

**Mutation testing** — use it to find tests that don't actually verify anything.

**Automate deploy and provision** — manual steps are where environments diverge and where outages start.

**Deploy checklist always up to date**

**Automate**: database migration, infrastructure

**Read books** Michael Feathers, *Working Effectively with Legacy Code* — the practical manual for everything described here. If you're inheriting a large codebase without tests, start there.

**Find your motto**: example from Picard engineering tip: Make it so every subsystem can be found and repaired manually, even if you need to crawl to reach it.


list of refactorings
--

1. svn -> git migration
2. release script
3. RPM build
4. puppet deploy
5. version in login page
6. let's encrypt
7. dashboard for AWS metrics
8. improved database backup/restore
9. improved logging (SLF4J)
10. removed lambdaj (rename as "custom pseudo functional library")
11. removed stateful DAOs + fixed unit-of-work problems
12. removed JBPM
13. hibernate migration 3.x -> 5.x
14. flyway + automatic db patches
15. fixed mysql myisam tables
16. fixed encoding of database
17. openoffice cleanups
18. new document generation using ODT templates
19. malta -> wro4j migration
20. DWH redesign (eventually consistent)
21. BCRYPT for password storage
22. removal of unused jars and classes
23. unit vs integration vs acceptance tests
24. java6 -> java7 -> java8 migration
25. build without custom maven repository
26. thread safety in cocoon
27. code quality (e.g. sonar)
28. hazelcast
29. fast XSLX parser/writer
