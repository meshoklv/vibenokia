#!/bin/sh
# Builds the game catalog: every games/src/<id> folder -> its own separate JAR+JAD.
# Run INSIDE the ghcr.io/mgruszkiewicz/docker-j2me-build container (cwd = games/):
#   docker run --rm -v "$(pwd):/workspace" ghcr.io/mgruszkiewicz/docker-j2me-build:latest sh build.sh
# You can pass a list of ids: sh build.sh tetris mines
set -e
WTK=/opt/j2me/wtk
JDK=/opt/j2me/jdk1.8.0_202
CP=$WTK/lib/midpapi20.jar:$WTK/lib/cldcapi11.jar:$WTK/lib/jsr75.jar:$WTK/lib/mmapi.jar
mkdir -p dist

LIST="$@"
[ -z "$LIST" ] && LIST=$(ls src)

for id in $LIST; do
    d=src/$id
    [ -f "$d/game.conf" ] || { echo "!! $id: no game.conf, skipping"; continue; }
    . ./$d/game.conf                       # NAME, VENDOR, DESC, CLASS, VERSION
    echo "=== $id: $NAME ($CLASS) ==="
    rm -rf build/$id tmp/$id
    mkdir -p build/$id tmp/$id
    $JDK/bin/javac -bootclasspath $CP -encoding UTF-8 -source 1.3 -target 1.3 \
        -d tmp/$id $(find $d -name '*.java')
    $WTK/bin/preverify -classpath $CP -d build/$id tmp/$id
    # game resources (images, levels) and icon
    [ -d "$d/res" ] && cp -r $d/res/* build/$id/ || true
    [ -f "$d/icon.png" ] && cp $d/icon.png build/$id/icon.png || true
    MF=build/$id/manifest.mf
    {
        echo "MIDlet-Name: $NAME"
        echo "MIDlet-Version: $VERSION"
        echo "MIDlet-Vendor: $VENDOR"
        echo "MIDlet-Description: $DESC"
        echo "MIDlet-Icon: /icon.png"
        echo "MIDlet-1: $NAME,/icon.png,$CLASS"
        echo "MicroEdition-Configuration: CLDC-1.1"
        echo "MicroEdition-Profile: MIDP-2.0"
        echo "MIDlet-Permissions: "
        echo ""
    } > $MF
    (cd build/$id && $JDK/bin/jar cfm ../../dist/$id.jar manifest.mf \
        $(ls | grep -v '^manifest.mf$'))
    SZ=$(stat -c%s dist/$id.jar)
    {
        echo "MIDlet-Name: $NAME"
        echo "MIDlet-Version: $VERSION"
        echo "MIDlet-Vendor: $VENDOR"
        echo "MIDlet-Description: $DESC"
        echo "MIDlet-Icon: /icon.png"
        echo "MIDlet-1: $NAME,/icon.png,$CLASS"
        echo "MicroEdition-Configuration: CLDC-1.1"
        echo "MicroEdition-Profile: MIDP-2.0"
        echo "MIDlet-Jar-URL: $id.jar"
        echo "MIDlet-Jar-Size: $SZ"
        echo "MIDlet-Permissions: "
    } > dist/$id.jad
    echo "    -> dist/$id.jar ($SZ b)"
done
rm -rf tmp
echo "DONE"
