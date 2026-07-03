#!/bin/bash
# Start a fake charger that connects to the gateway and plays the charge flow.
# Usage: ./run-simulator.sh [deviceId] [host] [tcpPort]
set -e
cd "$(dirname "$0")"
source ./env.sh
[ -f target/charger-tcp-gateway-1.0.0.jar ] || mvn -q -DskipTests package
exec java -cp target/charger-tcp-gateway-1.0.0.jar cn.nblinks.teask.simulator.ChargerSimulator "$@"
