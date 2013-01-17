#!/bin/bash -e

rlwrap=$(which rlwrap) || "" &> /dev/null
java="$rlwrap $JAVA_HOME/bin/java $JAVA_OPTS"

$java -jar target/shen.java-*-standalone.jar
