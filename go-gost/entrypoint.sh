#!/bin/sh
set -eu

mkdir -p /etc/gost/certs
exec /usr/local/bin/tms-node "$@"
