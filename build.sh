#!/bin/bash

name="FC Bot"
jarname="fcbot-1"
manifest=Manifest.txt
target=16
classPath="lib/javacord-3.4.0-shaded.jar:lib/tomlj-1.0.0.jar"
sourcePath=
extraOpts=

scriptdir=$(dirname "$0")

pushd "$scriptdir" > /dev/null

echo "building $name"

# prepare paths

if [ ! -z "$classPath" ]; then
	classPathArg="-classpath $classPath"
fi
if [ ! -z "$sourcePath" ]; then
	sourcePath="-sourcepath $sourcePath"
fi

# build

rm -f "$jarname.jar"

if [ -e "tmp/build" ]; then
	rm -R "tmp/build"/*
fi

javac -d tmp/build --release $target $classPathArg $sourcePath $extraOpts $(find src -type f -name '*.java') $(find src-server -type f -name '*.java')

if [ $? -ne 0 ]; then
	echo "build failed"
	exit 1
fi

# jar <- src

pushd tmp/build > /dev/null

if [ -z "$manifest" ]; then
	jar cf "../../$jarname.jar" *
else
	jar cfm "../../$jarname.jar" "../../$manifest" *
fi

if [ $? -ne 0 ]; then
	echo "jar creation failed"
	exit 1
fi

popd > /dev/null

# done

echo "build successful"
popd > /dev/null
exit 0
