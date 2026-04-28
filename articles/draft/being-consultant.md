out the tar pit
===

TODO: original title by 2017 in Notes.app

why?
---

This is a success story. Not a triumphant one — more the kind where you keep your head down for two years, make one small improvement at a time, and eventually look up to find the system actually works. The inspiration is *The Phoenix Project*[^phoenix] — minus the novel format. The goal here is simpler: document what happened, in case it's useful to someone standing at the same starting point.

- to document a success story (never lose the hope and never give up)
- inspiration is the book https://www.amazon.com/Phoenix-Project-DevOps-Helping-Business/dp/0988262592
  (minus the novel part)
- celebrate an awesome team

[^phoenix]: Gene Kim, Kevin Behr, George Spafford — *The Phoenix Project* (2013).


context
---

The project had been handed over to two junior developers with minimal knowledge transfer. The original authors were gone. Senior developers at the company knew about it and kept their distance — the project had a reputation. What remained was 250,000 lines of Java, a 90 MB WAR file, and a production system going down roughly once a day. Apache Cocoon 2.1, Struts, Spring MVC, Hibernate 3.2, Drools, jBPM — a decade of framework choices stacked on top of each other, glued together by custom libraries with no documentation and no one left to ask. Getting it running locally took me a week. The juniors had learned, through experience, that the safest move was to touch as little as possible.

- inherited a big enterprise project 250KLOC 90 MB war
- mysql with mix of innodb / myisam
- java5 cocoon struts springmvc
- hibernate 3.2 (hibernate 5 was already out)
- drools + jbpm
- unsupported custom libraries built in house (no documentation)
- Java reflections and proxies: very complex and fragile


bad points
--

The problems weren't just technical. Passwords were stored in plain text. Releases were done manually — deploy, patch the database by hand, hope nothing breaks. The build process required tribal knowledge that lived only in people's heads, and those people had left. Unit tests existed in the Maven configuration but were disabled. There was no CI, no integration tests, no structured logging — errors surfaced through `ex.printStackTrace()` or, worse, by email. Empty `catch` blocks were everywhere. Apache Maven and Ant JARs had somehow ended up on the production classpath. Onboarding a new developer meant days of undocumented setup rituals. The team wasn't incompetent — they were paralyzed by a system that punished curiosity.

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


good points
--

Not everything was broken. The team was already on AWS, which gave us flexibility without needing physical infrastructure. SVN was in use with sane branching defaults — at least history was preserved. There was a backup/restore culture, which meant the database wasn't a gamble. And someone, at some point, had started splitting the monolith into separate modules — incomplete, but a direction worth continuing.

- AWS
- svn with sane defaults
- culture of continuous restore backup
- starting division of the big monolith


team
--

When I joined, it was two junior backend developers and one frontend developer. No QA, no sysadmins. By the time I left, the team had grown to five backend developers, two frontend, and two QA. Still no sysadmins — which, three years in, meant three of us were managing 24 machines across three clusters and two master/slave database replicas in our spare attention.

The juniors were good developers who had been set up to fail. They knew the system well enough to keep it alive but had no framework for improving it. The first priority wasn't technical — it was giving them enough confidence to make a change without fearing they'd take down production.

- 2 -> 5 backend dev
- 1 -> 2 frontend
- 0 -> 2 qa
- no sysadmins


tactics
--

The first instinct when inheriting a system like this is to rewrite everything. That instinct is wrong. Joel Spolsky called it "the single worst strategic mistake that any software company can make"[^spolsky] — and the reasoning holds: the old system contains years of accumulated domain knowledge. Bugs that turned into features. Edge cases silently handled. Compensations for upstream failures. Throwing it away means losing all of that, and you won't know what you lost until it's missing in production.

[^spolsky]: Joel Spolsky, [*Things You Should Never Do, Part I*](https://www.joelonsoftware.com/2000/04/06/things-you-should-never-do-part-i/) (2000).

Instead, the approach was cultural before technical. Empower the developers to make changes. Replace fear with a process: if something breaks, understand why, fix it, and share what you learned. Every bug is an opportunity to understand the system better, not a sign that someone should have been more careful. Over time, this shift mattered more than any individual refactoring.

On the technical side: fail fast on broken invariants, add post-condition checks, and when in doubt, do less. Complexity was already the enemy — every change that added more of it made the next change harder.

- changing culture: empower the dev team
- let it crash + fail fast on broken invariants — post-invariants for the win
- less is more
- continuous improvement: every bug is an opportunity to learn and share


months 0–6: first steps
---

The first concrete change was removing Struts and consolidating on Spring MVC. Not because Struts was the biggest problem — it wasn't — but because having two web frameworks in the same application was unnecessary complexity with no payoff. Incremental changes from the start: no big-bang refactorings, no feature freezes. The system stayed in production throughout. At this point we had two environments: production and pre-production, two machines total.

- removal of struts: moved to spring mvc
- start to breaking the monolith: incremental changes
- two envs: prod + preprod, 2 machines in total


months 6–12
---

With a baseline of stability, we started clearing the underbrush. Unused classes, JAR conflicts, disabled tests. The JAR hell was particularly bad — Apache Maven and Ant artifacts on the production classpath, multiple copies of the same dependency under different group IDs, version conflicts surfacing as runtime errors with no clear cause. We untangled it incrementally. Logging moved from `printStackTrace` and email alerts to SLF4J with Logback. The build became a single command. The data warehouse job, which ran weekly and reliably died with out-of-memory errors, got its first attention.

- removing of unused classes
- enabled unit tests
- jar hell (maven ant on the production classpath, multiple jar of same dep with different group id)
- logging to SLF4J + logback
- removed most of printStackTrace
- single command build
- dwh job once per week with several oom


months 12–18
--

Infrastructure started getting attention. We introduced Puppet for AWS EC2 provisioning and switched to RPM builds via a Maven plugin, replacing the manual deployment rituals with something repeatable. A UAT environment came online — for the first time, there was a place to verify changes before they hit production. Drools, a rules engine that had been used for a small part of the business logic, was removed and replaced with straightforward Java. The data warehouse moved from weekly batch to incremental updates triggered on change. We also started forward-compatibility work for Java 8, already mainstream elsewhere but not yet adopted here.

- puppet for AWS EC2 infra
- RPM build using a maven plugin
- UAT env + training
- removal of drools
- starting to review the internal architecture
- incremental dwh triggered on change
- forward compatibility with java8


months 18–24
--

Continuous integration arrived, along with a migration from SVN to Git. Both changed how the team worked more than any code change had. Jenkins meant every commit was verified automatically; Git meant branching was cheap enough to actually use. The codebase moved to Java 7. All new code required unit tests — not as a rule handed down, but as a shared expectation the team had started to own. We introduced Sonar and IntelliJ IDEA inspections for static analysis; the number of subtle bugs they surfaced was striking. JavaMelody went in for runtime monitoring. Stateful DAOs — a pattern that had caused unit-of-work problems throughout the codebase — were systematically removed.

The milestone that stood out most: the team started treating bugs as opportunities to understand the system rather than fires to extinguish.

- jenkins + continuous integration
- migration to git
- cocoon upgrade
- all new code with unit tests
- manual QA
- using sonar + idea inspections: very useful, many subtle bugs discovered
- using javamelody
- removing stateful DAOs
- upgrade to java7

*My favorite: team starting to see bugs as opportunities.*


months 24–30
---

The infrastructure side caught up with the application side. CentOS 7 replaced the old OS; `systemctl` replaced the handwritten bash scripts managing services. Hibernate upgraded from 3.2 to 3.6. Flyway was introduced to manage database migrations — before this, schema changes were applied by hand with no versioning, which meant different environments could silently diverge. MyISAM tables in MySQL were converted to InnoDB, restoring transactional guarantees that had been missing. The data warehouse got a MySQL read replica for eventually-consistent reporting. Hazelcast came in for distributed locking across the cluster. The boy scout rule — leave the code better than you found it — had become a team habit.

- upgrade centos 7 + systemctl replacing bash scripts
- upgrade hibernate to 3.6
- messing with many unversioned databases -> flywaydb
- dropped myisam tables from mysql (all tables are innodb)
- wro4j manual minification of js less before commit
- eventually consistent dwh + mysql readonly replica
- boy scout rule FTW
- cluster: using hazelcast for distributing locks
- tomcat 7 upstream package


months 30–36
---

Integration tests and automated acceptance tests replaced a purely manual QA process. jBPM, the workflow engine, was removed — its use case didn't require it. A straightforward state machine, written from scratch and fully covered by integration tests, replaced it with a fraction of the complexity. Java 8 went to production everywhere. Let's Encrypt certificates replaced the manual certificate management. Load testing ran for the first time, giving numbers instead of guesses when discussing performance.

- integration tests + automatic acceptance tests
- removal of jbpm: integration test -> switch to custom state machine (very trivial one)
- new env: integration
- java8 everywhere upgrade in production
- rolling let's encrypt certificates
- continuous improvement kaizen
- load testing


month 36+
---

By this point the system looked almost nothing like what we had inherited. The WAR file was down to 60 MB. The codebase was at 180,000 lines — 70,000 fewer than when we started, despite three years of new features. There were over 200 database migrations in Flyway, every schema change tracked and repeatable. Production outages had been absent for months. We were deploying several times a week. Twenty-four machines across three clusters, two master/slave replicas — still no sysadmin. The last stretch was quieter: internal cleanups, a new external integration, the first zero-downtime production deployment.

After three years, I left the company. The team was in good shape: confident, autonomous, and shipping regularly. What I found on day one and what I left behind were barely recognizable as the same system.

This wasn't my first lead role. But in retrospect it was one of the most satisfying — not because of the technical work, but because of watching the junior developers grow. They went from being afraid to touch anything to taking ownership of the system, making decisions independently, and treating problems as something to solve rather than something to survive.

- 24 machines with 3 clusters and 2 master/slave replica... still no sysadmin :)
- several deploys per week
- no production outages for months
- monitoring via cloudwatch/kibana
- preparing for hibernate 5 upgrade
- 180KLOC for 60 MB war
- 200+ database migrations
- several integrations with external services Soap/rest/excel over http :)
- many internal cleanups (e.g. blob handling)
- new integration with external system
- deploy in production without downtime
- preparing to java9
- Left the company…


bad patterns
--

These appeared constantly across the codebase and are worth naming explicitly, because they recur in most legacy codebases:

**Log and rethrow** — catching an exception, logging it, and re-throwing it produces duplicate stack traces and obscures the origin of the error. Either handle it or let it propagate.

**Swallowed exceptions** — empty `catch` blocks, or catches that only log a message without preserving the exception, hide failures silently. Sonar was particularly effective at surfacing these.

**Resource leaks** — streams, connections, and other resources not closed in `finally` blocks or try-with-resources. Java 7's try-with-resources statement exists precisely for this.

**Unnecessary complexity** — overly generic solutions, deep inheritance hierarchies, abuse of reflection and proxies. Simple code that is easy to read and verify is worth more than clever code that is hard to debug.

**Useless tests** — tests that only verify happy paths, or that duplicate implementation rather than testing behavior. Mutation testing is a useful tool for finding them.

- log and rethrow
- don't wrap exceptions (sonar really shines here)
- resource leaks: try resource
- complex solution: write simple code that is easy to verify and debug
- abuse of generics
- useless tests (mutation testing)


recommended techniques
--

These are the practices that moved the needle most over two years:

**Incremental refactoring** — no big-bang rewrites. Every change ships to production. If a refactoring is too large to merge incrementally, split it.

**Test before replace** — before removing or replacing a component, write tests that capture its behavior. The tests become the specification for the replacement.

**Segregate subsystems** — keep transactions, logging, retry logic, and background jobs independent. Circular dependencies between subsystems make changes expensive.

**Minimal design** — throw away what isn't needed. The best code is the code that doesn't exist.

**Mutation testing** — use it to find tests that don't actually verify anything.

**Automate deploy and provision** — manual steps are where environments diverge and where outages start.

- [ ] incremental refactoring
- [ ] merge large scale refactorings incrementally (no rush)
- [ ] keep design minimal (throw away extraneous elements)
- [ ] mutation testing
- [ ] test it before replace
- [ ] segregate subsystems: transaction, log files and retention policy, retry jobs. avoid circular dependencies
- [ ] deploy checklist
- [ ] automation deploy and provision


books
--

Michael Feathers, *Working Effectively with Legacy Code* — the practical manual for everything described here. If you're inheriting a large codebase without tests, start there.

> Picard engineering tip: Make it so every subsystem can be found and repaired manually, even if you need to crawl to reach it.

- legacy code of Michael Feathers


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
