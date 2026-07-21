#!/usr/bin/env bash
# Guard the README "Compatibility" table against drifting from the build.
#
# "Tested up to" for a dependency is the highest version the build actually
# exercises: the pom property (Dependabot keeps that current) or any override
# pinned in the .github/workflows/compat.yml matrix, whichever is higher.
# Comparison is major.minor only — the table says "2.26.x" on purpose, and a
# patch bump must not turn CI red for a documentation nit.
#
# On mismatch, edit the Compatibility table in README.md (see CONTRIBUTING.md).
set -euo pipefail

cd "$(dirname "$0")/.."

fail=0

pom_property() {
  mvn -q help:evaluate -DforceStdout -Dexpression="$1" 2>/dev/null
}

# Highest -D<property>=X override exercised by the compat matrix (may be empty).
matrix_max() {
  grep -oE -- "-D$1=[0-9]+(\.[0-9]+)*" .github/workflows/compat.yml \
    | cut -d= -f2 | sort -V | tail -n1 || true
}

# "Tested up to" cell for the row whose first cell starts with $1, stripped of
# spaces (e.g. "2.26.x"). The pattern is anchored to the row's first cell so
# rows in other tables (the benchmark table's "Logback INFO ...") can't match.
readme_tested_up_to() {
  { grep -E "^\| $1" README.md || true; } | head -n1 \
    | awk -F'|' '{gsub(/ /, "", $4); print $4}'
}

major_minor() {
  echo "$1" | cut -d. -f1,2
}

check_row() {
  local display="$1" pattern="$2" property="$3"
  local pom matrix highest table
  pom="$(pom_property "$property")"
  matrix="$(matrix_max "$property")"
  highest="$(printf '%s\n%s\n' "$pom" "$matrix" | grep -v '^$' | sort -V | tail -n1)"
  table="$(readme_tested_up_to "$pattern")"
  if [[ -z "$table" ]]; then
    echo "README compatibility table: could not find the $display row" >&2
    fail=1
  elif [[ "$(major_minor "$table")" != "$(major_minor "$highest")" ]]; then
    echo "README compatibility table: $display says $table but the build tests up to $highest" >&2
    fail=1
  fi
}

check_row "Logback" 'Logback \|' "logback.version"
check_row "Log4j2" 'Log4j2 \|' "log4j2.version"
check_row "Spring Boot" 'Spring Boot ' "spring-boot.version"

# Java: the floor ("17+") tracks the pom's compiler release; "Tested up to"
# tracks the highest java-version in the CI matrices.
java_floor_pom="$(pom_property maven.compiler.release)"
java_floor_table="$(grep -E '^\| Java \|' README.md | awk -F'|' '{gsub(/[ +]/, "", $3); print $3}')"
if [[ "$java_floor_table" != "$java_floor_pom" ]]; then
  echo "README compatibility table: Java floor says ${java_floor_table}+ but pom compiles for release $java_floor_pom" >&2
  fail=1
fi

java_max_ci="$(grep -hoE 'java(-version)?: *\[?[0-9, ]+' .github/workflows/ci.yml .github/workflows/compat.yml \
  | grep -oE '[0-9]+' | sort -n | tail -n1)"
java_table="$(readme_tested_up_to 'Java \|')"
if [[ "$java_table" != "$java_max_ci" ]]; then
  echo "README compatibility table: Java says tested up to $java_table but CI tests up to $java_max_ci" >&2
  fail=1
fi

if [[ "$fail" -ne 0 ]]; then
  echo "" >&2
  echo "Fix: update the Compatibility table in README.md (major.minor precision, e.g. '2.26.x')." >&2
  exit 1
fi

echo "README compatibility table matches the build."
