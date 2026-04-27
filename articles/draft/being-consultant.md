out the tar pit
===

TODO: original title by 2017 in Notes.app

why?
---

- to document a success story (never lose the hope and never give up)
- inspiration is the book https://www.amazon.com/Phoenix-Project-DevOps-Helping-Business/dp/0988262592
  (minus the novel part)
- celebrate an awesome team


context
---

inherited a big enterprise project 250KLOC 90 MB war
mysql with mix of innodb / myisam
java5 cocoon struts springmvc
hibernate 3.2 (2015 was the year of hibernate 5)
drools + jbpm
unsupported custom libraries built in house (no documentation)
Java reflections and proxies: very complex and fragile

bad points
--
manual release + deploy + manual patch db
custom security framework
custom libraries for everything without documentation and original developers out
poor security practices (plain text passwords)
custom packages for java and tomcat
unit tests disabled in maven
no integration tests
no continuos integration
logging via email / ex.printStackTrace()
many empty try catch blocks
jar hell (apache maven / apache ant in the production classpath of a webapp)
frequent outages in production (once per day)
build from scratch requires obscure voodoo practices
  onboarding a new
team fears changes

good points
--
AWS
svn with sane defaults
culture of continuous restore backup
starting division of the big monolith

team
--
2 -> 5 backend dev
1 -> 2 frontend
0 -> 2 qa
no sysadmins


tactis
--
changing culture: empower the dev team
let it crash  + fail fast on broken invariants
  Post-invariants for the win
less is more
continuous improvement => every bug is opportunity to learn / share it back


begin of 2015: first steps
---

removal of struts: moved to spring mvc
start to breaking the monolith: incremental changes
two envs. prod + preprod 2 machine in total

mid 2015
---
removing of unused classes
enabled unit tests
jar hell (maven ant on the production classpath, multiple jar of same dep with different group id)
logging to SLF4J + logback
removed most of printstacktrace
single command build
dwh job once per week with several oom

late 2015
--
puppet for AWS EC2 infra
RPM build using a maven plugin
UAT env + training
removal of drools
starting to review the internal architecture
incremental dwh triggered on change
forward compatibility with java8

early 2016
--

jenkins + continuous integration
migration to git
cocoon upgrade
all new code with unit tests
manual QA
using sonar + idea inspections: very useful  many subtle bugs discovered
using javamelody
removing stateful DAOs
upgrade to java7

my favorite: team starting to see bug as opportunities!


mid 2016
---
upgrade centos 7  + systemctl replacing bash scripts
upgrade hibernate to 3.6
messing with many unversioned databases -> flywaydb
Dropped myisam tables from mysql (all tables are innodb)
wro4j manual minification of js less before commit
eventually consistent dwh  + mysql readonly replica
boy scout rule FTW
cluster: using hazelcast for distributing locks
tomcat 7 upstream package


late 2016
---
integration tests + automatic acceptance tests
removal of jbpm: integration test -> switch to custom state machine (very trivial one)
new env: integration
java8 everywhere upgrade in production
rolling let's encrypt certificates
continuous improvement kaizen
load testing

early 2017
---
24 machines with 3 clusters and 2 master/slave replica... still no sysadmin :)
several deploys per week
no production outages for months
monitoring via cloudwatch/kibana
preparing for hibernate 5 upgrade
180KLOC for 60 MB war
200+ database migrations
several integrations with external services Soap/rest/excel over http :)

mid 2017
---
many internal cleanups (e.g. blob handling)
new integration with external system
deploy in production without downtime
preparing to java9

Late 2017
--
Left the company…

bad patterns
--
log and rethrow
don't wrap exceptions (sonar really shines here)
resource leaks: try resource
complex solution: write simple code that is easy to verify and debug
abuse of generics
useless tests (mutation testing)

recommended techniques
--
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

legacy code of Michael feathers


Picard engineering tip: Make it so every subsystem can be found and repaired manually, even if you need to crawl to reach it.


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

