#!/usr/bin/env bash
set -euo pipefail

studio=${1:?Usage: $0 /path/to/jaspersoftstudio}
root=$(cd "$(dirname "$0")/../.." && pwd)
plugin=$(find "$studio/plugins" -maxdepth 1 -name 'net.sf.jasperreports_7.0.6.final.jar' -print -quit)
digester_bundle=$(find "$studio/plugins" -maxdepth 1 -name 'com.jaspersoft.studio.bundles.commons-digester_*.jar' -print -quit)
beanutils_bundle=$(find "$studio/plugins" -maxdepth 1 -name 'com.jaspersoft.studio.bundles.commons-beanutils_*.jar' -print -quit)
jackson_bundle=$(find "$studio/plugins" -maxdepth 1 -name 'com.jaspersoft.studio.bundles.jackson_*.jar' -print -quit)
for file in "$plugin" "$digester_bundle" "$beanutils_bundle" "$jackson_bundle"; do
    test -f "$file" || { echo "Missing Jaspersoft Studio 7.0.6 component: $file" >&2; exit 1; }
done

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/studio-libs"
(
    cd "$tmp/studio-libs"
    unzip -q "$plugin" 'lib/*.jar'
)
unzip -p "$digester_bundle" commons-digester-2.1.jar > "$tmp/digester.jar"
beanutils_entry=$(unzip -Z1 "$beanutils_bundle" | grep '^commons-beanutils-.*\.jar$' | head -1)
unzip -p "$beanutils_bundle" "$beanutils_entry" > "$tmp/beanutils.jar"
mkdir -p "$tmp/jackson"
(
    cd "$tmp/jackson"
    unzip -q "$jackson_bundle" '*.jar'
)

classpath="$tmp/digester.jar:$tmp/beanutils.jar"
while IFS= read -r jar; do
    classpath="$classpath:$jar"
done < <(find "$tmp/jackson" -type f -name '*.jar' | sort)
while IFS= read -r jar; do
    classpath="$classpath:$jar"
done < <(find "$tmp/studio-libs" -type f -name '*.jar' | sort)
while IFS= read -r jar; do
    classpath="$classpath:$jar"
done < <(find "$studio/plugins" -type f -name '*.jar' | sort)
mkdir -p "$tmp/classes" "$tmp/converted"
javac -proc:none -cp "$classpath" -d "$tmp/classes" \
    "$root/tools/jasperreports7-converter/LegacySourceConverter.java"
classpath="$tmp/classes:$classpath"

report_root=${BOKFRI_REPORT_ROOT:-"$root/src/main/resources/reports/report"}
while IFS= read -r source; do
    relative=${source#"$report_root/"}
    destination="$tmp/converted/$relative"
    mkdir -p "$(dirname "$destination")"
    java -cp "$classpath" com.jaspersoft.jasperreports.legacy.xml.LegacySourceConverter \
        "$source" "$destination" "$relative"
done < <(find "$report_root" -type f \( -name '*.jrxml' -o -name '*.jrtx' \) | sort)

while IFS= read -r converted; do
    relative=${converted#"$tmp/converted/"}
    cp "$converted" "$report_root/$relative"
done < <(find "$tmp/converted" -type f | sort)
