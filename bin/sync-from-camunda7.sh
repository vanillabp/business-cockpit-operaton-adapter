#!/usr/bin/env bash
#
# Makes the code of this repository again from a commit of the Business Cockpit extension for
# Camunda 7, and commits the result to the branch 'generated'.
#
# The branch 'generated' holds nothing but what this script writes. 'main' merges it, so a
# Camunda 7 change reaches 'main' as an ordinary merge. Conflicts appear only where 'main' has
# its own fixes on top. CONTRIBUTING.md, section "Following the Camunda 7 extension", says what
# to do before and after the merge.
#
# What the script does, in this order:
#
#   1. It clones the Camunda 7 extension and checks out the given commit.
#   2. It copies that tree into a worktree of 'generated'. The files in KEPT_BY_MAIN stay out,
#      because 'main' writes its own. The recipe file and the script which lists the type
#      renames come from the checkout this script runs in, not from the Camunda 7 extension.
#   3. It runs Operaton's OpenRewrite recipe. That one renames what belongs to the engine:
#      packages, Maven coordinates, the getters of the model API.
#   4. It lists the type renames and runs VanillaBP's recipe rewrite/vanillabp-operaton.yml.
#      That one leaves the Operaton version to the Operaton adapter and renames what belongs
#      to VanillaBP: the adapter the extension plugs into, the Java package, the classes and
#      the names in strings.
#   5. It formats the result with the Spotless rules of the repository.
#   6. It commits the result as "generated from BC C7 <full sha>". It does not push.
#
# Usage:
#   bin/sync-from-camunda7.sh <camunda7 commit or tag>
#
# Environment:
#   C7_REPOSITORY   where the Camunda 7 extension is cloned from (default: its repository on
#                   GitHub)
#   MAVEN_ARGS      Maven reads this itself, in every call. For instance
#                   '-Dmaven.repo.local=/tmp/m2' keeps the run out of your local repository.
#
# The recipe reads the dependencies of the Camunda 7 extension, and one of them is the Camunda 7
# adapter. All of them come from Maven Central and its snapshot repository, which need no login.
#
# The two recipes take a few minutes together. On an error the script stops and leaves
# its working directory behind, so the error can be read there. It prints where that is.

set -euo pipefail

# The versions of the plugin running the recipes and of Operaton's recipe, the same as in the
# Operaton adapter. Raise them when Maven Central has newer ones, in a commit of their own,
# and run the script again.
REWRITE_PLUGIN=org.openrewrite.maven:rewrite-maven-plugin:6.46.1
OPERATON_RECIPE=org.operaton:migrate-camunda-recipe:1.0.1

# Files of the Camunda 7 extension which this repository writes itself. They are not copied, so
# 'generated' never holds them and a merge never touches them.
KEPT_BY_MAIN=(
  .git
  .github
  AGENTS.md
  CLAUDE.md
  CONTRIBUTING.md
  DECISIONS.md
  README.md
  UPGRADE.md
  renovate.json
)

# What a recipe run must not do. The build of a recipe run is not the build of the adapter:
# Spotless would complain about the POMs the recipe just changed, and the code does not
# compile until the fixes of 'main' are on top, if 'main' has any.
NO_BUILD=(
  -Dspotless.check.skip=true
  -Dmaven.main.skip=true
  -Dmaven.test.skip=true
  -Djacoco.skip=true
  -Dmaven.javadoc.skip=true
  -Dmaven.source.skip=true
)

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <commit or tag of the Camunda 7 extension>" >&2
  exit 2
fi
c7_ref=$1

# CDPATH is cleared because cd prints the directory it found through CDPATH.
repository=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
c7_repository=${C7_REPOSITORY:-https://github.com/vanillabp/business-cockpit-camunda7-adapter.git}

work=$(mktemp -d)
echo "Working in $work"

# 1. The Camunda 7 extension at the given commit.
git clone --quiet "$c7_repository" "$work/c7"
git -C "$work/c7" checkout --quiet "$c7_ref"
c7_sha=$(git -C "$work/c7" rev-parse HEAD)
echo "Camunda 7 extension at $c7_sha"

# 2. A worktree of 'generated', emptied and filled with the tree of the Camunda 7 extension. The branch is
# created without history where it does not exist yet.
tree="$work/generated"
if git -C "$repository" rev-parse --verify --quiet refs/heads/generated >/dev/null; then
  git -C "$repository" worktree add --quiet "$tree" generated
elif git -C "$repository" rev-parse --verify --quiet refs/remotes/origin/generated >/dev/null; then
  git -C "$repository" worktree add --quiet -b generated "$tree" origin/generated
else
  git -C "$repository" worktree add --quiet --orphan -b generated "$tree"
fi
find "$tree" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
excludes=()
for kept in "${KEPT_BY_MAIN[@]}"; do
  excludes+=(--exclude "/$kept")
done
rsync -a "${excludes[@]}" "$work/c7/" "$tree/"
# The recipe works on a copy outside the tree. Its text rules would otherwise rename the
# words they look for in their own file.
recipe="$work/vanillabp-operaton.yml"
cp "$repository/rewrite/vanillabp-operaton.yml" "$recipe"

cd "$tree"

# 3. Operaton's recipe.
mvn --batch-mode --no-transfer-progress "${NO_BUILD[@]}" \
  "$REWRITE_PLUGIN:run" \
  -Drewrite.recipeArtifactCoordinates="$OPERATON_RECIPE" \
  -Drewrite.activeRecipes=org.operaton.rewrite.MigrateFromCamunda \
  -Drewrite.exportDatatables=false

# 4. VanillaBP's recipe.
"$repository/bin/write-type-renames.sh" "$tree" "$recipe"
mvn --batch-mode --no-transfer-progress "${NO_BUILD[@]}" \
  "$REWRITE_PLUGIN:run" \
  -Drewrite.configLocation="$recipe" \
  -Drewrite.activeRecipes=io.vanillabp.cockpit.operaton.FromCamunda7 \
  -Drewrite.exportDatatables=false

# The formatting rules of the repository, applied once here, so that 'main' never has to
# carry a formatting change of generated code. The recipes write correct code, but not always
# in the layout Spotless checks.
mvn --batch-mode --no-transfer-progress com.diffplug.spotless:spotless-maven-plugin:apply

# The recipe and the script which wrote its type renames go into the commit as they were
# used, so 'generated' shows how it was made.
mkdir -p "$tree/rewrite" "$tree/bin"
cp "$recipe" "$tree/rewrite/vanillabp-operaton.yml"
cp "$repository/bin/write-type-renames.sh" "$tree/bin/"

# 6. The commit, without the build output of the recipe runs. git keeps no empty
# directories, so the old package directories the type renames emptied disappear here.
find "$tree" -name target -type d -prune -exec rm -rf {} +
git add --all .
if git diff --cached --quiet && git rev-parse --verify --quiet HEAD >/dev/null; then
  # the same commit of the Camunda 7 extension with the same recipes gives the same tree
  echo "Nothing changed: 'generated' already holds what $c7_sha gives."
else
  git commit --quiet -m "generated from BC C7 $c7_sha"
  echo "Committed $(git rev-parse HEAD) to 'generated'. Push it, then merge it into main."
fi
cd "$repository"
git worktree remove --force "$tree"
rm -rf "$work"
