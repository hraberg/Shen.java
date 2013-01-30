#!/bin/bash -e

rlwrap=$(which rlwrap) || "" &> /dev/null
java="$rlwrap $JAVA_HOME/bin/java $JAVA_OPTS"
shen="find . -name shen.java-*.jar"

test -z `$shen` && mvn package
$java -jar `$shen`
