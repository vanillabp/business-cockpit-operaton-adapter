# Working on business-cockpit-operaton-adapter

The VanillaBP Business Cockpit integration for Operaton, built as an extension of VanillaBP
Version 2, on Spring Boot and on Quarkus.

Read [`README.md`](./README.md) first: it says which module holds what, what is deliberately absent
and why, and how to build.

## Most of the code is made by a script

Nearly every file here is made from
[business-cockpit-camunda7-adapter](https://github.com/vanillabp/business-cockpit-camunda7-adapter)
by `bin/sync-from-camunda7.sh`. The result sits on the branch `generated`, and `main` merges it.
Decision 3 of [`DECISIONS.md`](./DECISIONS.md) says why, and
[`CONTRIBUTING.md`](./CONTRIBUTING.md#following-the-camunda-7-extension) says how.

So before you change a file, ask where the change belongs:

- A change which is right for Camunda 7 as well belongs in the Camunda 7 extension. It comes here
  with the next sync.
- A name the recipe writes wrongly, or an engine call which Operaton answers differently, belongs in
  the recipe `rewrite/vanillabp-operaton.yml`. Then run the sync, so `generated` shows the result.
- Only what exists in this repository alone is changed on `main` directly: the documentation, the
  workflows and the module `migration-tests`.

A fix written by hand into a generated file meets a conflict at the next merge of `generated`, or
it is lost.

## What a POM hands an application

A tool that only translates our source belongs in scope `provided`, and the scope stands at the
declaration in the module using it. Lombok is such a tool, and an annotation processor is another.
Somebody added one dependency to see their user tasks in the cockpit. Every jar they did not ask
for is one more thing to ship and to answer a CVE report about.

Writing `<optional>true</optional>` in a `dependencyManagement` does not do it. Maven copies a
managed version, scope and exclusions into a dependency, but not the optional flag. So the POM we
publish says nothing at all about that dependency.

The two cases differ, and a sentence written for one does not fit the other. A declaration without
a scope hands the jar to every application, and that is a defect. An `optional` in a
`dependencyManagement` hands out nothing as long as no module declares the dependency. It is a
promise nobody ever keeps, and it breaks on the day the first module declares it. This repository
had the second case for Lombok, and the VanillaBP adapters had the first.

## This is an extension, not a BPMS adapter

An extension joins the deployment pipeline of the VanillaBP core and implements
`ExtensionWiringService` from `io.vanillabp:vanillabp-extension-spi`. The adapter SPI is what a
BPMS adapter implements, and it has no business in this repository. The pipeline both of them run
in, and the order they run in, is described once for everybody in
[`ADAPTER-AUTHORS.md`](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/ADAPTER-AUTHORS.md)
of the platform repository.

What this extension does with the pipeline belongs to the Business Cockpit, and the cockpit's own
repository is [vanillabp/business-cockpit](https://github.com/vanillabp/business-cockpit). The
platform-neutral half of the extension lives there as `extensions-commons` and is consumed here as
a published artifact, never copied.

## Two names for the same thing

The wiki calls this an adapter. This repository calls it an extension. Both are right, and which
word fits depends on where you stand.

Somebody using the Business Cockpit adds one dependency and sees their user tasks in the cockpit.
From there this is a cockpit adapter, sitting next to the BPMS adapter which runs their workflows.
The VanillaBP core sees something else: a bean which joins its deployment pipeline through
`vanillabp-extension-spi`, and a bean like that is what the core calls an extension.

So the end-user documentation says adapter and never extension. The documentation in this repository
says extension where the core's own term is meant, and adapter where it is about what a user adds to
their application. Say which of the two you mean, rather than assuming the reader knows.

## The decision log is binding

[`DECISIONS.md`](./DECISIONS.md) holds the decisions several places in this repository rely on. It
is the ONLY thing the code is allowed to cite, in the plain greppable form
`see decision 3 in the repository's DECISIONS.md`, and only entries of THIS repository. The code
which came from the Camunda 7 extension cites that repository's log, and it says so:
`see decision 3 in the Camunda 7 extension's DECISIONS.md`. The recipe writes that form.

Read it before you change behaviour. An entry is not background reading, it is the reason the code
around it looks the way it does, so a change which contradicts one is wrong until the entry says
otherwise.

**A decision is changed or replaced only after asking.** Where your change would make an entry
untrue, stop and put the question to the maintainer before you write the change. If the answer is
yes, the same commit updates the log: the old entry STAYS, marked as superseded and naming the
entry which replaced it, and the new decision takes the next free number. Numbers are never reused
and never renumbered, because a citation in an older release still points at them.

Adding an entry has the same rule. A decision earns a number when several places rely on it and
copying the explanation to each of them would rot; anything smaller is a comment where it belongs,
and anything larger is documentation.

## How we write

Most people who read this repository read English as a second language, and so does the
maintainer. Long sentences, rare words and stacked nouns slow them down. Write so that
nobody has to read a sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice.
The common word instead of the rare one: `use` instead of `leverage`, `about` instead of
`regarding`, `so` instead of `consequently`, `has` instead of `possesses`. A technical term
stays a technical term, but say what it means the first time it turns up, and write an
abbreviation out once. If a sentence trips you up when you read it aloud, rewrite it.

This holds for every English text here: the README files, `DECISIONS.md`, this file, the
Javadoc and comments which explain something, in the main code and in the tests, and the
texts of commits and pull requests.
It holds for the [wiki](https://github.com/vanillabp/business-cockpit-operaton-adapter/wiki)
as well, because the wiki clone has no `AGENTS.md` of its own.

Nothing a program reads is renamed for the sake of language. Class and method names,
configuration keys, artifact coordinates and the headlines of decision log entries stay as
they are, because code, tests and other repositories point at them.

Before and after, from this repository:

> The alternative would be execution listeners written into every model, which means rewriting
> models their author wrote and missing every workflow whose end nobody modelled.

became

> The alternative would be execution listeners written into every model. That means rewriting
> models their author wrote, and it misses every workflow whose end nobody modelled.

This one:

> Whether the application declares a `@UserTaskDetailsProvider` for that task decides only how
> much the cockpit is told about it, never whether it is told at all.

became

> A `@UserTaskDetailsProvider` for that task decides how much the cockpit is told about it. It
> never decides whether the cockpit is told at all.

And this one:

> Where an event the cockpit reads is missing, the boot ends with a message naming the engine's
> level, the events it does not write, the lowest level which writes them and the fact that the
> engine's own default already does - so an application which never touched the level will never
> see it.

became

> Where an event the cockpit reads is missing, the boot ends. The message names the engine's
> level, the events it does not write, the lowest level which writes them, and the fact that the
> engine's own default already does. An application which never touched the level will therefore
> never see it.

## Before you open a pull request

A number your branch hands out can be taken by the time you open the pull request. Another branch
was open at the same time and got there first. So check your numbers against `origin/main` and
against every open pull request, before the pull request exists.

It went wrong twice on 2026-09-13 in the cockpit repository: two branches claimed one number,
which had to become 19 and 20, and two more claimed the next, which had to become 21 and 22.
Both times it showed up at the merge, which is the worst moment for it. A merge happens on
GitHub, and a `see decision 21` in a Java file cannot be changed there.

The check:

```bash
bin/check-decision-numbers.sh
```

The script reports and changes nothing. By hand it is:

```bash
git fetch origin
git show origin/main:DECISIONS.md | grep -E '^#+ [0-9]+\. '   # the numbers already taken
gh pr list --state open
gh pr diff <n> | grep -E '^\+#+ [0-9]+\. '                    # for each open pull request
```

`gh pr diff` takes no path argument, so the grep does the filtering.

If your number is taken, your entry gets the next free one, and you correct every citation of it
in the code and in the documentation.

Read each citation before you change it. Not every `see decision <n>` in the branch is about your
decision. A branch can cite a number somebody else handed out long ago, and that citation stays
as it is. A search and replace over the branch turns a right reference into a wrong one.

None of this breaks the rule that a number is never renumbered. That rule is about a merged
number, which a citation in a released artifact points at. Until the pull request is merged,
nothing outside the branch has seen the number, so correcting it costs no more than the branch.

Every other running number is checked the same way. The story prompts are such a series. They are
kept outside this repository, so they are checked where they are kept.

## What code may point at

Nothing which a later change can invalidate without anything noticing: no story or prompt number,
no issue or pull-request number, no chat transcript, no person. Those record a conversation at a
point in time. A decision entry lives next to the code and is overhauled in the same commit, which
is what makes it citable.

Where a name can carry the reason, the name is the better fix. Where it cannot, a comment says why
in its own words, complete where it stands. Only what several places have to carry becomes an entry
in the log.

Commit messages and pull-request descriptions may cite whatever they like. They are records of a
point in time themselves.

## Formatting

`mvn spotless:apply` before every commit. It formats the POMs and the Markdown as well as the Java,
and the build fails on a violation.
