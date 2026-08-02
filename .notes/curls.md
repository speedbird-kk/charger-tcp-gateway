## Start slot 3

```bash
./run-gateway.sh
./run-simulator.sh 10000064
curl -X POST http://localhost:8080/devices/10000064/start \
  -H 'Content-Type: application/json' -d '{"slotNo":3,"money":500}'
```

## Start slot 5

```bash
curl -X POST http://localhost:8080/devices/10000064/start \
  -H 'Content-Type: application/json' -d '{"slotNo":5,"money":500}'
```

## Stop slot 5

```bash
curl -X POST http://localhost:8080/devices/10000064/stop \
  -H 'Content-Type: application/json' -d '{"slotNo":5}'
```