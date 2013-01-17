#!/bin/bash -e

rlwrap=$(which rlwrap) || "" &> /dev/null
java="$rlwrap $JAVA_HOME/bin/java $JAVA_OPTS"

if [ ! -e target/shen.java-*-standalone.jar ]; then
    mvn package
fi

$java -jar target/shen.java-*-standalone.jar
