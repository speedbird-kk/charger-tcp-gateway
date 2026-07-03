#!/bin/bash
# Start the TCP gateway (chargers connect on :6666, control plane on :8080).
set -e
cd "$(dirname "$0")"
source ./env.sh
[ -f target/charger-tcp-gateway-1.0.0.jar ] || mvn -q -DskipTests package
exec java -cp target/charger-tcp-gateway-1.0.0.jar cn.nblinks.teask.gateway.GatewayApp
