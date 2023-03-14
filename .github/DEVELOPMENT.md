# Development

Developers should read [the development section of the website](https://trino.io/development),
which covers thing like development philosophy and contribution process.

More information about the writing and building the documentation can
be found in the [docs module](../docs).

* [Commits and pull requests](#commits-and-pull-requests)
* [Code style](#code-style)
* [Additional IDE configuration](#additional-ide-configuration)
* [Building the Web UI](#building-the-web-ui)
* [CI pipeline](#ci-pipeline)

## Commits and pull requests

### Format Git commit messages

When writing a Git commit message, follow these [guidelines](https://chris.beams.io/posts/git-commit/).

### Git merge strategy

Pull requests are usually merged into `master` using the  [`rebase and merge`](https://docs.github.com/en/github/collaborating-with-pull-requests/incorporating-changes-from-a-pull-request/about-pull-request-merges#rebase-and-merge-your-pull-request-commits) strategy.

A typical pull request should strive to contain a single logical change (but not
necessarily a single commit). Unrelated changes should generally be extracted
into their own PRs.

If a pull request does consist of multiple commits, it is expected that every
prefix of it is correct. That is, there might be preparatory commits at the 
bottom of the stack that don't bring any value by themselves, but none of the
commits should introduce an error that is fixed by some future commit. Every
commit should build and pass all tests.

Commit messages and history are also important, as they are used by other
developers to keep track of the motivation behind changes. Keep logical diffs
grouped together in separate commits, and order commits in a way that explains
the progress of the changes. Rewriting and reordering commits may be a necessary
part of the PR review process as the code changes. Mechanical changes (like
refactoring and renaming)should be separated from logical and functional
changes. E.g. deduplicating code or extracting helper methods should happen in a
separate commit from the commit where new features or behavior is introduced.
This makes reviewing the code much easier and reduces the chance of introducing
unintended changes in behavior.

## Code Style

We recommend you use IntelliJ as your IDE. The code style template for the
project can be found in the [codestyle](https://github.com/airlift/codestyle)
repository along with our general programming and Java guidelines. 

To run checkstyle and other maven checks before opening a PR: `./mvnw validate`

In addition to those you should also adhere to the following:

### Readability

The purpose of code style rules is to maintain code readability and developer
efficiency when working with the code. All the code style rules explained below
are good guidelines to follow but there may be exceptional situations where we
purposefully depart from them. When readability and code style rule are at odds,
the readability is more important.

### Consistency

Keep code consistent with surrounding code where possible.

### Alphabetize

Alphabetize sections in the documentation source files (both in the table of
contents files and other regular documentation files).

### Use streams

When appropriate, use the stream API. However, note that the stream
implementation does not perform well so avoid using it in inner loops or
otherwise performance sensitive sections.

### Categorize errors when throwing exceptions.

Categorize errors when throwing exceptions. For example, `TrinoException` takes
an error code as an argument, `TrinoException(HIVE_TOO_MANY_OPEN_PARTITIONS)`.
This categorization lets you generate reports so you can monitor the frequency
of various failures.

### Add license header

Ensure that all files have the appropriate license header; you can generate the
license by running `mvn license:format`.

### Prefer String formatting

Consider using String formatting (printf style formatting using the Java
`Formatter` class): `format("Session property %s is invalid: %s", name, value)`
(note that `format()` should always be statically imported).  Sometimes, if you
only need to append something, consider using the `+` operator.  Please avoid
`format()` or concatenation in performance critical sections of code.

### Avoid ternary operator

Avoid using the ternary operator except for trivial expressions.

### Define class API for private inner classes too

It is suggested to declare members in private inner classes as public if they
are part of the class API.

### Avoid mocks

Do not use mocking libraries. These libraries encourage testing specific call
sequences, interactions, and other internal behavior, which we believe leads to
fragile tests.  They also make it possible to mock complex interfaces or
classes, which hides the fact that these classes are not (easily) testable. We
prefer to write mocks by hand, which forces code to be written in a certain
testable style.

### Use AssertJ

Prefer AssertJ for complex assertions.

### Use Airlift's `Assertions`

For thing not easily expressible with AssertJ, use Airlift's `Assertions` class
if there is one that covers your case.

### Avoid `var`

Using ``var`` is discouraged.

### Prefer Guava immutable collections

Prefer using immutable collections from Guava over unmodifiable collections from
JDK. The main motivation behind this is deterministic iteration.

### Maintain production quality for test code

Maintain the same quality for production and test code.

### Avoid abbreviations

Please avoid abbreviations, slang or inside jokes as this makes harder for
non-native english speaker to understand the code. Very well known
abbreviations like `max` or `min` and ones already very commonly used across
the code base like `ttl` are allowed and encouraged.

### Avoid default clause in exhaustive enum-based switch statements

Avoid using the `default` clause when the switch statement is meant to cover all
the enum values. Handling the unknown option case after the switch statement
allows static code analysis tools (e.g. Error Prone's `MissingCasesInEnumSwitch`
check) report a problem when the enum definition is updated but the code using
it is not.

## Additional IDE configuration

When using IntelliJ to develop Trino, we recommend starting with all of the
default inspections, with some modifications.

Enable the following inspections:

- ``Java | Internationalization | Implicit platform default charset``,
- ``Java | Control flow issues | Redundant 'else'`` (including
  ``Report when there are no more statements after the 'if' statement`` option),
- ``Java | Class structure | Utility class is not 'final'``,
- ``Java | Class structure | Utility class with 'public' constructor``,
- ``Java | Class structure | Utility class without 'private' constructor``.

Disable the following inspections:

- ``Java | Performance | Call to 'Arrays.asList()' with too few arguments``,
- ``Java | Abstraction issues | 'Optional' used as field or parameter type``,
- ``Java | Data flow | Boolean method is always inverted``.

Update the following inspections:

- Remove ``com.google.common.annotations.Beta`` from ``JVM languages | Unstable API usage``.

Enable errorprone ([Error Prone Installation#IDEA](https://errorprone.info/docs/installation#intellij-idea)):
- Install ``Error Prone Compiler`` plugin from marketplace,
- Check the `errorprone-compiler` profile in the Maven tab

This should be enough - IDEA should automatically copy the compiler options from
the POMs to each module. If that doesn't work, you can do it manually:

- In ``Java Compiler`` tab, select ``Javac with error-prone`` as the compiler,
- Update ``Additional command line parameters`` and copy the contents of
  ``compilerArgs`` in the top-level POM (except for ``-Xplugin:ErrorProne``)
  there
  - Remove the XML comments...
  - ...except the ones which denote checks which fail in IDEA, which you should
    "unwrap"
- Remove everything from the list under ``Override compiler parameters per-module``

Note that the version of errorprone used by the IDEA plugin might be older than
the one configured in the `pom.xml` and you might need to disable some checks
that are not yet supported by that older version. When in doubt, always check
with the full Maven build (``./mvnw clean install -DskipTests -Perrorprone-compiler``).

### Language injection in IDE

In order to enable language injection inside Intellij IDEA, some code elements
can be annotated with the `@org.intellij.lang.annotations.Language` annotation.
To make it useful, we recommend:

- Set the project-wide SQL dialect in ``Languages & Frameworks | SQL Dialects``
  "Generic SQL" is a decent choice here,
- Disable inspection ``SQL | No data source configured``,
- Optionally disable inspection ``Language injection | Language mismatch``.

Even if the IDE does not support language injection this annotation is useful
for documenting the API's intent. Considering the above, we recommend annotating
with `@Language`:

- All API parameters which are expecting to take a `String` containing an SQL
  statement (or any other language, like regular expressions),
- Local variables which otherwise would not be properly recognized by IDE for
  language injection.

## Building the Web UI

The Trino Web UI is composed of several React components and is written in JSX
and ES6. This source code is compiled and packaged into browser-compatible
Javascript, which is then checked in to the Trino source code (in the `dist`
folder). You must have [Node.js](https://nodejs.org/en/download/) and
[Yarn](https://yarnpkg.com/en/) installed to execute these commands. To update
this folder after making changes, simply run:

    yarn --cwd core/trino-main/src/main/resources/webapp/src install

If no Javascript dependencies have changed (i.e., no changes to `package.json`),
it is faster to run:

    yarn --cwd core/trino-main/src/main/resources/webapp/src run package

To simplify iteration, you can also run in `watch` mode, which automatically
re-compiles when changes to source files are detected:

    yarn --cwd core/trino-main/src/main/resources/webapp/src run watch

To iterate quickly, simply re-build the project in IntelliJ after packaging is
complete. Project resources will be hot-reloaded and changes are reflected on
browser refresh.

## Releases

Trino aims for frequent releases, generally once per week. This is a goal but
not a guarantee, as critical bugs may lead to a release being pushed back or
require an extra emergency release to patch the issue.

At the start of each release cycle, a GitHub issue is filed and pinned to track
all necessary release notes. For example, see [the issue for Trino 395](https://github.com/trinodb/trino/issues/13913).
In addition, a release notes pull request is updated and maintained throughout
the week, tracking all merged commits to ensure every change is properly
documented and noted. This uses the [release note template](../docs/release-template.md),
with changes in each section arranged to have new features first, performance
improvements second, and bugfixes third. See [the release notes for 395](https://github.com/trinodb/trino/pull/13975)
as an example.

Once it is time to release, the release process is kicked off. A code freeze is
announced on the Trino Slack in the #releases channel, and then a maintainer
utilizes the [release scripts](https://github.com/trinodb/release-scripts) to
update Trino to the next version.

## CI pipeline

### Maven offline mode

The CI pipeline runs most Maven commands in offline mode to avoid dependency on Maven Central.

Before calling Maven a job should execute the `./.github/actions/setup` compound action to
download all the dependencies it will require. 

It uses two complementary tools to detect and download Maven dependencies, 
but some plugin dependencies are not expressed in the tree hierarchy.

These dynamic dependencies must be listed as `<DynamicDependency>` entries in the root `pom.xml`.
