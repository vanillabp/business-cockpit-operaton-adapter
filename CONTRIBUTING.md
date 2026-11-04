# Contributing

This repository is the Business Cockpit adapter for Operaton. It is an extension of VanillaBP
Version 2 and runs in the workflow application, on Spring Boot and on Quarkus. It tells the cockpit
server about the user tasks and workflows it sees. How to use it is in the
[wiki](https://github.com/vanillabp/businesscockpit-operaton-adapter/wiki). This file is for
somebody who changes the code.

Nearly all of the code is made from the
[Business Cockpit adapter for Camunda 7](https://github.com/vanillabp/businesscockpit-camunda7-adapter)
by a script. Read [Following the Camunda 7 extension](#following-the-camunda-7-extension) before you
change a line which came from there.

## From a bug to a pull request

Found a bug? You can hand it to a coding agent.

1. Clone [`development-workspace`](https://github.com/vanillabp/development-workspace) with
   `git clone --recurse-submodules`. It holds the VanillaBP repositories side by side. Its
   `.claude/skills` folder holds the skills Claude Code reads when it starts at the root of the
   workspace.
2. Start your coding agent at the root of the workspace and describe the bug: what you did, what
   you expected and what happened instead.
3. The agent builds a scenario which reproduces the bug, looks for the cause and fixes it. If the
   cause is in code which came from the Camunda 7 extension, the fix goes there first. Then it
   pushes a branch to your fork and opens a pull request from there.
4. The VanillaBP team reviews the pull request and merges it.

You need a GitHub account, the GitHub CLI logged in with `gh auth login`, a fork of the repository
you change (the agent can create it with `gh repo fork`), and Java 21 and Maven for the build. The
submodules are cloned over SSH. If GitHub has no SSH key of yours, run
`git config --global url."https://github.com/".insteadOf "git@github.com:"` before you clone.

## Before you change the code

Read [`README.md`](./README.md) first. It says what each module holds and how to build.
[`DECISIONS.md`](./DECISIONS.md) holds the decisions several places rely on. Its first entry says
that the decisions of the Camunda 7 extension hold here too. Read both logs before you change
behaviour, and ask before a change makes one of their entries untrue.
[`AGENTS.md`](./AGENTS.md) holds the rules a change follows, in the form an agent reads.

## Snapshots

The build reads the platform, the cockpit and the Operaton adapter from the snapshot repository of
Maven Central, which needs no login.

The module `migration-tests` is different. It runs the Camunda 7 adapter next to this extension, and
the snapshots of the Camunda 7 adapter are only in the GitHub Packages registry of the Camunda
Community Hub, `https://maven.pkg.github.com/camunda-community-hub/vanillabp-camunda7-adapter`.
GitHub Packages wants a login even for reading. So you need a personal access token (classic) with
`read:packages`, and a server entry with the id `vanillabp-camunda7-adapter` in
your `~/.m2/settings.xml`:

```xml
<server>
  <id>vanillabp-camunda7-adapter</id>
  <username>your GitHub login</username>
  <password>the token</password>
</server>
```

Without it, leave the module out: `mvn install -pl '!migration-tests'`. The sync script needs the
same entry, because the Camunda 7 extension it starts from depends on the Camunda 7 adapter. The
workflows get the token from the secrets `VANILLABP_USER_NAME` and `VANILLABP_USER_TOKEN`. A pull
request from a fork gets no secrets, so its build of that module fails. A maintainer then pushes the
branch to this repository.

## Following the Camunda 7 extension

The branch `generated` holds what `bin/sync-from-camunda7.sh` makes from one commit of the Camunda 7
extension, and nothing else. Its commits are named `generated from BC C7 <full sha>`. `main` merges
`generated`. Decision 3 says why.

To bring a newer state of the Camunda 7 extension over:

1. Read the log of the Camunda 7 extension since the commit named by the newest commit on
   `generated`. Read its `DECISIONS.md` as well. A new entry there may need one here (decision 1).
2. Run the script with the commit you want, from a checkout of this repository. It needs the
   server entry of [Snapshots](#snapshots), and it takes a few minutes:

   ```bash
   bin/sync-from-camunda7.sh <commit of businesscockpit-camunda7-adapter>
   ```

   It commits to the local branch `generated` and does not push. If nothing changed, it says so.

3. Look at the diff of `generated` against its previous commit. Search it for what the recipes
   cannot judge:

   - `Camunda` or `camunda` which is left in a string, a message or a comment, and which means the
     engine of this extension;
   - a raw namespace read (`getAttributeValueNs`, `attributeNS`) or a `getCamunda...` call. The
     Operaton adapter reads both BPMN namespaces (its decision 2). The extension has none today, and
     a new one has to read both namespaces too;
   - a new call to the engine whose answer may differ in Operaton. Run the tests to find those.
     A place found that way gets a rule in the recipe `WhereOperatonAnswersDifferently`, so
     `generated` stays buildable.
4. Push `generated`, merge it into a branch from `main`, build with `mvn install`, and open a pull
   request.

A rule of the recipe is changed in `rewrite/vanillabp-operaton.yml` on `main`. Then run the script
again with the same commit. The new `generated` shows exactly what the rule changed. The script
copies the recipe and `bin/write-type-renames.sh` into `generated`, so `generated` shows how it was
made. The script itself lives on `main` only.

The type renames between the two marker lines of the recipe are written by
`bin/write-type-renames.sh`, one per class whose name holds `Camunda7`. Do not edit them by hand.
The classes of the Operaton adapter which the extension uses are renamed by the text rules of the
recipe. The Operaton adapter names its classes the same way, `Camunda7EngineFacts` becomes
`OperatonEngineFacts`, so the text rule is enough. If the Operaton adapter ever names a class
differently, the build of `generated` breaks at the import, and the recipe gets a rule for it.

## Building and testing

```bash
mvn spotless:apply
mvn install
```

Run `install`, not `install verify`. `install` already runs every phase of `verify`. Naming both
runs two lifecycles per module and reports every compiler warning twice. It has to be `install`,
because the Quarkus tests load the modules of this repository from the local Maven repository.

Nothing needs Docker. The Operaton engine runs embedded, on an in-memory database.

`mvn dependency:tree` shows no artifact of `org.camunda.bpm` outside `migration-tests`. If one
shows up, a dependency brings Camunda 7 along, and the tests of that module may run against the
wrong engine.
