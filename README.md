![VanillaBP](./readme/vanillabp-headline.png)

# VanillaBP Business Cockpit adapter for Operaton

[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

Spring Boot [![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fbusinesscockpit-operaton-adapter%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/businesscockpit-operaton-adapter/spring-boot-report)<br>
Quarkus [![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fbusinesscockpit-operaton-adapter%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/businesscockpit-operaton-adapter/quarkus-report)

This repository holds the [VanillaBP Business Cockpit](https://github.com/vanillabp/business-cockpit)
integration for [Operaton](https://operaton.org/), built as an extension of
[VanillaBP](https://www.vanillabp.io) Version 2. The cockpit shows user tasks and business cases
to business staff, so it has to learn what happens inside the workflow engine. This adapter is the
half which runs in the workflow application. It watches the user-task and workflow lifecycle in the
embedded Operaton engine, asks the application for the business details of what it saw, and hands
the result to the cockpit server.

Operaton is a fork of Camunda 7. This repository is made from the
[Business Cockpit adapter for Camunda 7](https://github.com/vanillabp/businesscockpit-camunda7-adapter),
and it follows that repository by a merge, see [How the code is made](#how-the-code-is-made).

## Status

The extension runs on both platforms against a real embedded Operaton engine. It builds on the
extension SPI of `adapter-platform-integration`, on the cockpit's `extensions-commons` and on the
[VanillaBP Operaton adapter](https://github.com/vanillabp/operaton-adapter). All of them are still
snapshots, so this repository is one too.

It was tested with `io.vanillabp:operaton-adapter` 2.0.0-SNAPSHOT, which brings Operaton 2.2.0.
This extension pins no Operaton version of its own. The engine comes with the adapter, so the two
always use the same Operaton (decision 2 in the repository's [`DECISIONS.md`](./DECISIONS.md)).

## What is here

The extension is split the way every VanillaBP adapter repository is split. The artifacts keep the
repository name as their prefix, so a jar of this repository is never mistaken for a jar of the
VanillaBP Operaton adapter it plugs into.

|         Module         |                       Artifact                        |                                   What is in it                                    |
|------------------------|-------------------------------------------------------|------------------------------------------------------------------------------------|
| `core`                 | `businesscockpit-operaton-adapter`                    | Everything which needs neither Spring nor Quarkus                                  |
| `spring-boot`          | `businesscockpit-operaton-adapter-spring-boot`        | One auto-configuration, one bean registrar, the tests against a booted application |
| `quarkus/runtime`      | `businesscockpit-operaton-adapter-quarkus`            | One CDI producer doing what the auto-configuration does                            |
| `quarkus/deployment`   | `businesscockpit-operaton-adapter-quarkus-deployment` | One build step, and the tests running the extension inside a Quarkus application   |
| `migration-tests`      | not published                                         | This extension next to the Camunda 7 extension in one application                  |
| `test-coverage-report` | not published                                         | The per-platform JaCoCo aggregation and the gate judging it                        |

What the core does, class by class:

- `OperatonCockpitWiring` takes part in VanillaBP's deployment pipeline for a workflow module which
  runs on Operaton, and remembers which BPMN processes were deployed.
- `OperatonWorkflowProcesses` is that memory. It is also the way back: an engine reports
  identifiers of its own, and this class says which workflow module and which plain process id the
  application meant by them.
- `OperatonCockpitCustomizer` is what the Operaton adapter asks per configured adapter id. It
  contributes the parse listener and the history event handler of that engine. It also ends the boot
  of an engine whose history level writes none of what the cockpit reads back.
- `OperatonUserTaskParseListener` attaches `OperatonUserTaskListener` to every user task, as a
  built-in listener of all four task events.
- `OperatonWorkflowHistoryHandler` turns the engine's process-instance history events into the
  cockpit's workflow events. An update which suspends or activates an instance becomes a suspension
  or an activation of every open user task of the instance and of its workflow. The engine calls no
  task listener for that.
- `OperatonCockpitEvents` is what both of them report through. It builds the identifiers and hands
  the event to the cockpit. The cockpit builds the report right away and writes one outbox entry.
- `OperatonEventBeingReported` holds what the engine handed over while that report is built. It is
  how the bridge answers out of the event instead of querying an engine which cannot answer yet.
- `OperatonCockpitBridge` answers everything the cockpit asks about a task or a workflow. At an
  event it answers out of the event. Where the application asks, through `BusinessCockpitService`,
  it queries the engine for the state of now.
- `OperatonScope` is how the extension asks what an engine calls things, rather than building a
  prefix or a tenant of its own. Two of those answers come from the Operaton adapter: the tenant a
  workflow module was deployed under, and whether the engine's work runs in the caller's
  transaction.

The reasons for these classes are the decisions of the Camunda 7 extension. This repository's
[`DECISIONS.md`](./DECISIONS.md) says so in its first entry and adds what is different here. What a
user of this extension has to know is in the
[wiki](https://github.com/vanillabp/businesscockpit-operaton-adapter/wiki).

## What is deliberately absent

The same as in the Camunda 7 extension. This extension rewrites no model: a listener is attached
while the engine parses a model, so the BPMN in your repository and the BPMN the engine gets are the
same. It has no persistence of its own. What has to survive a crash is the outbox entry VanillaBP
already provides. And it has no configuration key of its own. What it has to know about an engine
it asks the Operaton adapter. So the Spring Boot jar ships no configuration metadata.

## Running next to the Camunda 7 extension

An application moves from Camunda 7 to Operaton with both adapters in one application: new
workflows start on Operaton, and the old ones finish on Camunda 7. The cockpit then needs both
extensions, this one and the Camunda 7 one. They have different names everywhere, so they do not
get in each other's way (decision 4).

One thing has to be done by hand. Camunda 7.24 and Operaton 2.2 bring two versions of the FEEL
engine library, and with both on the classpath the Camunda 7 engine does not start. Remove
Camunda's copy from both Camunda 7 artifacts:

```xml
<dependency>
  <groupId>org.camunda.community.vanillabp</groupId>
  <artifactId>camunda7-adapter-spring-boot</artifactId>
  <exclusions>
    <exclusion>
      <groupId>org.camunda.feel</groupId>
      <artifactId>feel-engine</artifactId>
    </exclusion>
  </exclusions>
</dependency>
<dependency>
  <groupId>io.vanillabp.businesscockpit</groupId>
  <artifactId>businesscockpit-camunda7-adapter-spring-boot</artifactId>
  <exclusions>
    <exclusion>
      <groupId>org.camunda.feel</groupId>
      <artifactId>feel-engine</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

Then let the Camunda 7 engine evaluate decisions with its older FEEL implementation, which needs no
Scala:

```java
@Bean
public Camunda7EngineCustomizer camunda7WithoutScalaFeel() {
  return new Camunda7EngineCustomizer() {
    @Override
    public void customize(String adapterId, ProcessEngineConfigurationImpl configuration) {
      configuration.setDmnFeelEnableLegacyBehavior(true);
    }
  };
}
```

This is decision 8 of the [Operaton adapter](https://github.com/vanillabp/operaton-adapter), and the
Operaton adapter's README says what it costs. The module `migration-tests` does exactly this, and
`OperatonNextToCamunda7Test` runs a user task on each engine and checks that the cockpit hears of
both through the right extension.

## Building

```bash
mvn install
```

`install` and not `install verify`: `install` runs every phase `verify` has, and naming both walks
two lifecycles per module. It has to be `install` rather than `package`, because the Quarkus tests
load the modules of this repository from the local Maven repository.

The tests boot real applications on both platforms: a Spring Boot context and a Quarkus application,
each with an embedded Operaton engine on H2, a cockpit server the test runs itself and the outbox in
between. No BPMS double and no mock of the engine.

The build reads the snapshots of the platform, of the cockpit and of the Operaton adapter from the
snapshot repository of Maven Central, `https://central.sonatype.com/repository/maven-snapshots/`. The
parent `io.vanillabp:release-parent` names that repository, and reading it needs no login.

`migration-tests` is the exception. It needs the Camunda 7 adapter, whose snapshots are only in the
GitHub Packages registry of the Camunda Community Hub. GitHub Packages asks for a token even for a
public package. [`CONTRIBUTING.md`](./CONTRIBUTING.md#snapshots) says how to give your build one,
and how to build without that module.

Snapshots of this repository go to the snapshot repository of Maven Central through the pipeline
described below, and releases go to Maven Central under the groupId `io.vanillabp.businesscockpit`,
like the rest of the Business Cockpit.

## How the code is made

Nearly all code of this repository is made by a script from the Business Cockpit adapter for
Camunda 7. `bin/sync-from-camunda7.sh` copies a commit of that repository, runs Operaton's
OpenRewrite recipe over it and then the recipe `rewrite/vanillabp-operaton.yml`, which writes this
extension's own names. The result goes to the branch `generated`, and `main` merges it. Whatever
this repository does differently sits on `main` on top. [`CONTRIBUTING.md`](./CONTRIBUTING.md) has
the procedure, and decision 3 has the reasons.

So a change to a file which came from the Camunda 7 extension belongs there first. A fix made only
here is lost at the next sync, or it meets a conflict.

## Test coverage

`mvn install` writes one aggregated JaCoCo report per platform:

1. Spring Boot, over `core` and `spring-boot`, into `test-coverage-report/spring-boot/report`
2. Quarkus, over `core`, `quarkus/runtime` and `quarkus/deployment`, into
   `test-coverage-report/quarkus/report`

Coverage is measured separately per platform, because the tests of one platform never run the
other platform's code. The core is in both reports. If one platform never reaches a class of the
core, that platform never runs the feature of that class. `migration-tests` measures nothing, see
decision 5.

The last module of the reactor is `test-coverage-report/coverage-gate`. It reads both reports and
breaks the build when a platform is below its threshold in the root POM:
`coverage.threshold.spring-boot` and `coverage.threshold.quarkus`, both 85 percent of covered
instructions. The rule is `coverage.rule`, 90 per platform, so a report between 85 and 90 passes
the build and still names a gap. The gate also checks that every module which writes coverage data
is part of a report. It prints what it measured on every run.

## What CI runs

`build.yaml` builds and tests a pull request, in a group per pull request.

`publish-snapshots.yaml` publishes the snapshot, and only for a push to `main`. It runs in a group
of its own, one publish at a time, and a publish which is already running is never cancelled. It
also publishes the two coverage reports to GitHub Pages, and the badges at the top of this page link
there.

`release.yaml` is started by hand and publishes to Maven Central from a release branch.

`nightly-build.yaml` runs `build.yaml` on `main` once a night, with `--update-snapshots`. So a
`main` which a new snapshot of the platform, of the cockpit or of the Operaton adapter breaks is
seen, even when nobody pushed here. A new Operaton minor reaches this repository that way. A red
night becomes one issue with the label `nightly-build`.

Every workflow reads the Camunda 7 adapter for `migration-tests` with the repository secrets
`VANILLABP_USER_NAME` and `VANILLABP_USER_TOKEN`, a token with `read:packages`.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by
[Phactum](https://www.phactum.at) with the intention of giving back to the community as it has
benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
