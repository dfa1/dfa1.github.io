#!/bin/bash
USER="root"
TARGET="$1"
shift
OPTIONS="$@"
PORT=${PORT:-22}
SSH="ssh -p $PORT -T"

set -e

if [ -z $TARGET ]; then
  echo "error: specify user@host, by default user is root"
  exit 1
fi

if [[ "$TARGET" != *@* ]]; then
  TARGET="$USER@$TARGET"
fi

./scripts/build-puppet-apply.sh $OPTIONS | $SSH ${TARGET}
