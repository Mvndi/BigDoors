#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# The CI version list is also the source of truth for local builds.
# Override a JDK executable with J_8/J_16/J_17/J_21/J_25, or set JAVA<n>_HOME.
BUILD_DIR="${BUILD_DIR:-nms_build}"
MAVEN_REPO="$HOME/.m2/repository"
force=false
if [[ "${1:-}" == --force ]]; then force=true; shift; fi
requested=("$@")
versions=()
java_versions=()
major=""
while IFS= read -r line; do
    if [[ "$line" =~ ^java([0-9]+): ]]; then
        major="${BASH_REMATCH[1]}"
    elif [[ "$line" =~ ^[[:space:]]*-[[:space:]]*\"([^\"]+)\" ]]; then
        versions+=("${BASH_REMATCH[1]}")
        java_versions+=("$major")
    fi
done < .github/nms-groups.yml

for requested_version in "${requested[@]}"; do
    found=false
    for version in "${versions[@]}"; do
        if [[ "$version" == "$requested_version" ]]; then found=true; break; fi
    done
    if ! $found; then echo "Unknown NMS version: $requested_version" >&2; exit 1; fi
done

find_java() {
    local major="$1" override="J_$1" env_home="JAVA${1}_HOME" candidate actual
    if [[ -n "${!override:-}" ]]; then
        printf '%s\n' "${!override}"
        return
    fi
    if [[ -n "${!env_home:-}" ]]; then
        printf '%s/bin/java\n' "${!env_home}"
        return
    fi
    for candidate in "/usr/lib/jvm/java-$major-openjdk/bin/java" \
        "$HOME"/.jdks/*/bin/java "${JAVA_HOME:-/nonexistent}/bin/java"; do
        [[ -x "$candidate" ]] || continue
        actual=$("$candidate" -XshowSettings:properties -version 2>&1 | \
            awk '/java.specification.version =/ {print $3}')
        if [[ "$actual" == "$major" || "$actual" == "1.$major" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    echo "Java $major not found. Set $override or $env_home to its JDK." >&2
    return 1
}

mkdir -p "$BUILD_DIR"
build_tools="$(realpath "$BUILD_DIR")/BuildTools.jar"
downloaded=false
for i in "${!versions[@]}"; do
    version="${versions[i]}"
    if (( ${#requested[@]} )); then
        selected=false
        for requested_version in "${requested[@]}"; do
            if [[ "$version" == "$requested_version" ]]; then selected=true; break; fi
        done
        $selected || continue
    fi
    cached="$MAVEN_REPO/org/spigotmc/spigot/$version-R0.1-SNAPSHOT"
    if ! $force && [[ -f "$cached/spigot-$version-R0.1-SNAPSHOT.jar" ]]; then
        echo "Using cached Spigot $version"
        continue
    fi
    java_path="$(find_java "${java_versions[i]}")"
    if ! $downloaded; then
        curl --fail --location --retry 2 \
            https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar \
            --output "$build_tools"
        downloaded=true
    fi
    dir="$(realpath "$BUILD_DIR")/Spigot$version"
    mkdir -p "$dir"
    echo "Building Spigot $version with $java_path (log: $dir/build.log)"
    # Build sequentially to bound memory use. A failed version stops setup and preserves its log.
    if ! (cd "$dir" && JAVA_HOME="$(dirname "$(dirname "$java_path")")" \
        "$java_path" -Xmx2G -jar "$build_tools" --rev "$version" --compile SPIGOT \
        > build.log 2>&1); then
        tail -n 60 "$dir/build.log" >&2
        exit 1
    fi
    echo "Finished building Spigot $version"
done

if [[ ! -f "$MAVEN_REPO/com/dansplugins/medieval-factions/5.2.0/medieval-factions-5.2.0.jar" ]]; then
    bash ./installDependencies.sh
fi
echo "NMS dependencies ready. Run mvn package with Java 25 installed (auto-discovered by Maven)."
