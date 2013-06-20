#!/bin/bash
# This bash script contains common shell functions and is included by all bash scripts
#

function die() {
    echo $1 >&2
    exit 1
}

export WP_BASE=../
export WP_CORE=$WP_BASE/wikapidia-core
export WP_LOADER=$WP_BASE/wikapidia-loader
export WP_MAPPER=$WP_BASE/wikapidia-mapper
export WP_PARENT=$WP_BASE/wikAPIdia-parent
export WP_PARSER=$WP_BASE/wikapidia-parser
export WP_UTILS=$WP_BASE/wikapidia-utils

[ -d ${WP_BASE} ] || die "missing base directory ${WP_BASE}"

for d in "${WP_CORE}" "${WP_LOADER}" "${WP_MAPPER}" "${WP_PARENT}" "${WP_PARSER}" "${WP_UTILS}"; do
    [ -d "$d" ] || die "missing module directory $d"
done


function compileJooq() {
    schema_dir=${WP_CORE}/src/main/resources/db/
    [ -d "$schema_dir" ] || die "missing sql schema directory $schema_dir"
    cat ${schema_dir}/*-schema.sql > ${schema_dir}/full_schema.sql
    cat ${schema_dir}/*-indexes.sql >> ${schema_dir}/full_schema.sql
    oldhash=$(cat ${schema_dir}/full_schema.hash | tr -d ' \n' )
    newhash=$(md5 -q ${schema_dir}/full_schema.sql)

    if [ "$oldhash" == "$newhash" ]; then
        echo "jooq schema is already up to date." >&2
        return
    fi

    (cd ${WP_CORE} && mvn sql:execute jooq-codegen:generate) ||
        die "jooq compilation failed"
    echo $newhash > ${schema_dir}/full_schema.hash
}

function compile() {
    echo compiling... &&
    compileJooq &&
    (cd ${WP_PARENT} && mvn compile ) ||
    die "compilation failed"

    echo finished compiling
}

function execClass() {
    class=$1
    shift

    localclasspathfile=./target/localclasspath.txt
    if ! [ -f $localclasspathfile ]; then
        die "missing local classpath file $localclasspathfile"
    fi
    read < $localclasspathfile REMOTE_CLASSPATH
    CMD="java -cp \"${REMOTE_CLASSPATH}:${LOCAL_CLASSPATH}\" $JAVA_OPTS $class $@"
    $CMD ||
    die "executing '$CMD' failed"
}

# source all util scripts here, so that other scripts only
# need to source this script to source everything
source ${WP_UTILS}/src/main/scripts/conf.sh


