# Decision log

The code of this repository points at the decisions below. A number is handed out once. It is never
reused or renumbered, so a citation always finds its entry. A decision which is overturned keeps its
entry. The entry is then marked as superseded and names the entry which replaced it.

Most of the code here is made from the Business Cockpit extension for Camunda 7 (decision 3). That
code cites the decisions of the Camunda 7 extension, and it says so:
`see decision 3 in the Camunda 7 extension's DECISIONS.md`. Code written for this repository cites
the entries below, in the usual form `see decision 2 in the repository's DECISIONS.md`.

### 1. The decisions of the Camunda 7 extension apply here, unless an entry below says otherwise

This extension does what the Camunda 7 extension does, with Operaton as the engine. Its code is made
from the Camunda 7 code, and the reasons for that code are written down in the
[decision log of the Camunda 7 extension](https://github.com/vanillabp/business-cockpit-camunda7-adapter/blob/main/DECISIONS.md).
They hold here as well. Copying them would give two texts which drift apart, so they stay there.

An entry below is about one of two things. Either Operaton differs from Camunda 7, or this repository
differs from the Camunda 7 repository. Where an entry below contradicts a decision of the Camunda 7
extension, the entry below wins in this repository.

A change to a decision of the Camunda 7 extension reaches this repository with the next sync
(decision 3). Whoever runs the sync reads the new entries of that log and asks whether one of them
needs an entry here.

The same holds one level down. The Operaton adapter this extension plugs into has its own
[decision log](https://github.com/vanillabp/operaton-adapter/blob/main/DECISIONS.md), and its
decisions about the engine are the ones this extension lives with.

### 2. The Operaton version comes from the Operaton adapter, and there is one line of this extension

The Camunda 7 extension pins Camunda in its own property `camunda.version`. This extension pins no
Operaton version. The core module does not declare the engine at all. It gets `operaton-engine`
through `io.vanillabp:operaton-adapter`, in the version the adapter pins. So the extension and the
adapter can never use two different Operaton versions in one application.

The extension uses engine classes below `.impl.`: the parse listener, the history event handler, the
command context. Operaton may change those in any minor. The adapter already has to follow every
Operaton minor for the same reason, and it follows only the newest one (decision 4 in the Operaton
adapter's log). So this extension follows the adapter and has no release lines of its own. Its
version moves with the other three Business Cockpit extensions.

The README says which adapter version, and so which Operaton version, the extension was tested
with. A new Operaton minor reaches this repository as a new snapshot of the adapter. The nightly
build then tests the extension against it.

### 3. The code is made from the Camunda 7 extension, and a merge brings each change over

The Camunda 7 extension keeps changing, and nearly all of its changes are wanted here too. Porting
each of them by hand would cost the most and would miss some. So the code is made again from a commit
of the Camunda 7 extension whenever it is to follow. This is the procedure of the Operaton adapter,
applied to the extension:

- `bin/sync-from-camunda7.sh` copies the tree of the Camunda 7 extension, runs Operaton's
  OpenRewrite recipe over it, then runs the recipe `rewrite/vanillabp-operaton.yml` for VanillaBP's
  own names (decision 6). It commits the result to the branch `generated` as
  `generated from BC C7 <sha>`.
- `generated` holds nothing else. `main` merges it. Everything this repository does differently sits
  on `main` as ordinary commits on top, so a conflict appears only where a change of the Camunda 7
  extension touches one of those places.
- Files which `main` writes itself are never copied: the workflows, `README.md`, `DECISIONS.md`,
  `AGENTS.md`, `CONTRIBUTING.md`, `CLAUDE.md`, `UPGRADE.md` and `renovate.json`. The script lists
  them.

A fix of generated code goes into the recipe, not onto `main`. Then `generated` itself builds, and a
merge never meets a fix by hand. The first sync needed one such fix: Operaton's
`Context.getCommandContext()` throws where no engine command runs, while Camunda 7 answers `null`.
The recipe `WhereOperatonAnswersDifferently` writes `Context.findCommandContext().orElse(null)`
instead. With it, `generated` passes every test of the Camunda 7 extension. What `main` adds on top
is this repository's own: the documentation, the workflows and the module `migration-tests`
(decision 7). `CONTRIBUTING.md` holds the procedure and what to check after each sync.

A test class of the Camunda 7 extension keeps the parts of its name which are not `Camunda7`, and
its test resources keep their paths, such as `c7-cockpit/processes`. A renamed path is one more line
which can conflict in a merge.

### 4. The extension has a name of its own everywhere - superseded by decision 6

*Superseded by decision 6: the Camunda 7 adapter is published as `io.vanillabp` now, not as
`org.camunda.community.vanillabp`. Everything else holds and is repeated there. The entry stays
because code and documentation pointed at it.*

The extension must be able to run next to the Camunda 7 extension in one application. That is how
an application moves from Camunda 7 to Operaton. Two classes with the same name on one classpath,
two Spring auto-configurations with the same name, or two Quarkus extensions with the same name
break that. So nothing of this extension carries the name of the Camunda 7 extension:

|          What          |                  Camunda 7 extension                  |            This extension             |
|------------------------|-------------------------------------------------------|---------------------------------------|
| Maven groupId          | `io.vanillabp.businesscockpit`                        | `io.vanillabp.businesscockpit`        |
| artifactIds            | `businesscockpit-camunda7-adapter...`                 | `businesscockpit-operaton-adapter...` |
| Java package           | `io.vanillabp.cockpit.camunda7`                       | `io.vanillabp.cockpit.operaton`       |
| class names            | `Camunda7...`                                         | `Operaton...`                         |
| Quarkus extension      | `vanillabp-business-cockpit-camunda7`                 | `vanillabp-business-cockpit-operaton` |
| adapter it plugs into  | `org.camunda.community.vanillabp:camunda7-adapter...` | `io.vanillabp:operaton-adapter...`    |
| adapter type it serves | `camunda7`                                            | `operaton`                            |

The groupId stays the one of the Business Cockpit, like the other extensions. The recipe
`rewrite/vanillabp-operaton.yml` writes all of these names, so a sync keeps them.
`OperatonNextToCamunda7Test` in `migration-tests` runs both extensions in one application.

### 5. The test with the Camunda 7 extension is a module of its own, and the Camunda 7 engine does without the Scala FEEL engine there - superseded by decision 7

*Superseded by decision 7: the Camunda 7 adapter publishes its snapshots to Maven Central now, so
the module needs no login and no token any more. Everything else holds and is repeated there. The
entry stays because code and documentation pointed at it.*

`migration-tests` runs this extension and the Camunda 7 extension in one Spring Boot application,
each with its adapter and its engine. It is a module of its own and not part of `spring-boot`, for
the reasons the Operaton adapter gives in its decision 7:

- `spring-boot` comes from the Camunda 7 extension. Its POM changes with every sync, and a module of
  this repository's own never meets a conflict there.
- The Camunda 7 adapter is a snapshot, and its snapshots are only in the GitHub Packages registry of
  the Camunda Community Hub, which wants a login even for reading. A module of its own keeps that
  need to one place. Every module before it builds without the login.

The module names that registry under the id `vanillabp-camunda7-adapter`. A
build needs a server entry with that id and a token with `read:packages`. Once the Camunda 7 adapter
has a release on Maven Central, the registry goes away.

Camunda 7.24 and Operaton 2.2 bring two versions of the FEEL engine, and with both on the classpath
the Camunda 7 engine does not start. The module does what decision 8 of the Operaton adapter says: it
removes Camunda's copy of the FEEL engine and lets the Camunda 7 engine evaluate decisions with its
older FEEL implementation. An application with both extensions needs the same two steps, and the
README shows them. The extensions themselves evaluate no decision, so they change nothing about this.

The module publishes nothing and has no coverage of its own in the reports. Its Spring Boot test
covers code which `spring-boot` already covers, and it would add the classes of the Camunda 7
extension to the Spring Boot report.

### 6. The extension has a name of its own everywhere, and the Camunda 7 adapter is `io.vanillabp`

The extension must be able to run next to the Camunda 7 extension in one application. That is how
an application moves from Camunda 7 to Operaton. Two classes with the same name on one classpath,
two Spring auto-configurations with the same name, or two Quarkus extensions with the same name
break that. So nothing of this extension carries the name of the Camunda 7 extension:

|          What          |          Camunda 7 extension          |            This extension             |
|------------------------|---------------------------------------|---------------------------------------|
| Maven groupId          | `io.vanillabp.businesscockpit`        | `io.vanillabp.businesscockpit`        |
| artifactIds            | `businesscockpit-camunda7-adapter...` | `businesscockpit-operaton-adapter...` |
| Java package           | `io.vanillabp.cockpit.camunda7`       | `io.vanillabp.cockpit.operaton`       |
| class names            | `Camunda7...`                         | `Operaton...`                         |
| Quarkus extension      | `vanillabp-business-cockpit-camunda7` | `vanillabp-business-cockpit-operaton` |
| adapter it plugs into  | `io.vanillabp:camunda7-adapter...`    | `io.vanillabp:operaton-adapter...`    |
| adapter type it serves | `camunda7`                            | `operaton`                            |

The groupId stays the one of the Business Cockpit, like the other extensions. Version 2 of the
Camunda 7 adapter is published as `io.vanillabp`, like the Operaton adapter. The recipe
`rewrite/vanillabp-operaton.yml` writes all of these names, so a sync keeps them.
`OperatonNextToCamunda7Test` in `migration-tests` runs both extensions in one application.

### 7. The test with the Camunda 7 extension is a module of its own, and the Camunda 7 engine does without the Scala FEEL engine there

`migration-tests` runs this extension and the Camunda 7 extension in one Spring Boot application,
each with its adapter and its engine. It is a module of its own and not part of `spring-boot`, for
one reason the Operaton adapter gives in its decision 7: `spring-boot` comes from the Camunda 7
extension. Its POM changes with every sync, and a module of this repository's own never meets a
conflict there.

The module reads the snapshot of the Camunda 7 adapter from the snapshot repository of Maven
Central, like every other snapshot. That needs no login.

Camunda 7.24 and Operaton 2.2 bring two versions of the FEEL engine, and with both on the classpath
the Camunda 7 engine does not start. The module does what decision 8 of the Operaton adapter says: it
removes Camunda's copy of the FEEL engine and lets the Camunda 7 engine evaluate decisions with its
older FEEL implementation. An application with both extensions needs the same two steps, and the
README shows them. The extensions themselves evaluate no decision, so they change nothing about this.

The module publishes nothing and has no coverage of its own in the reports. Its Spring Boot test
covers code which `spring-boot` already covers, and it would add the classes of the Camunda 7
extension to the Spring Boot report.
