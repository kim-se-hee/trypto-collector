#!/bin/bash
set -e

INFLUX_TOKEN="${DOCKER_INFLUXDB_INIT_ADMIN_TOKEN}"
ORG="${DOCKER_INFLUXDB_INIT_ORG}"
BUCKET="${DOCKER_INFLUXDB_INIT_BUCKET}"

# ticker_raw → candle_1m (매 1분, raw tick price를 OHLC로 집계)
influx task create \
  --org "$ORG" \
  --token "$INFLUX_TOKEN" \
  -f /dev/stdin <<'FLUX'
option task = {name: "aggregate_candle_1m", every: 1m, offset: 10s}

data = from(bucket: "ticker")
  |> range(start: -1m5s)
  |> filter(fn: (r) => r._measurement == "ticker_raw" and r._field == "price")

o = data
  |> aggregateWindow(every: 1m, fn: first, createEmpty: false, timeSrc: "_start")

h = data
  |> aggregateWindow(every: 1m, fn: max, createEmpty: false, timeSrc: "_start")

l = data
  |> aggregateWindow(every: 1m, fn: min, createEmpty: false, timeSrc: "_start")

c = data
  |> aggregateWindow(every: 1m, fn: last, createEmpty: false, timeSrc: "_start")

o |> set(key: "_field", value: "open") |> set(key: "_measurement", value: "candle_1m")
  |> to(bucket: "ticker", org: "trypto")

h |> set(key: "_field", value: "high") |> set(key: "_measurement", value: "candle_1m")
  |> to(bucket: "ticker", org: "trypto")

l |> set(key: "_field", value: "low") |> set(key: "_measurement", value: "candle_1m")
  |> to(bucket: "ticker", org: "trypto")

c |> set(key: "_field", value: "close") |> set(key: "_measurement", value: "candle_1m")
  |> to(bucket: "ticker", org: "trypto")
FLUX

echo "Task created: aggregate_candle_1m"

create_ohlc_task() {
  local task_name=$1
  local every=$2
  local offset=$3
  local range_start=$4
  local source=$5
  local measurement=$6
  local window_offset=${7:-}

  local agg_offset=""
  if [ -n "$window_offset" ]; then
    agg_offset=", offset: ${window_offset}"
  fi

  influx task create \
    --org "$ORG" \
    --token "$INFLUX_TOKEN" \
    -f /dev/stdin <<FLUX
option task = {name: "${task_name}", every: ${every}, offset: ${offset}}

data = from(bucket: "${BUCKET}")
  |> range(start: ${range_start})
  |> filter(fn: (r) => r._measurement == "${source}")

o = data
  |> filter(fn: (r) => r._field == "open")
  |> aggregateWindow(every: ${every}, fn: first, createEmpty: false, timeSrc: "_start"${agg_offset})
  |> last()

h = data
  |> filter(fn: (r) => r._field == "high")
  |> aggregateWindow(every: ${every}, fn: max, createEmpty: false, timeSrc: "_start"${agg_offset})
  |> last()

l = data
  |> filter(fn: (r) => r._field == "low")
  |> aggregateWindow(every: ${every}, fn: min, createEmpty: false, timeSrc: "_start"${agg_offset})
  |> last()

c = data
  |> filter(fn: (r) => r._field == "close")
  |> aggregateWindow(every: ${every}, fn: last, createEmpty: false, timeSrc: "_start"${agg_offset})
  |> last()

union(tables: [o, h, l, c])
  |> set(key: "_measurement", value: "${measurement}")
  |> to(bucket: "${BUCKET}", org: "${ORG}")
FLUX

  echo "Task created: ${task_name}"
}

#            task_name                every  offset  range_start  source       measurement   window_offset
create_ohlc_task "aggregate_candle_5m"   "5m"   "30s"  "-5m30s"     "candle_1m"  "candle_5m"
create_ohlc_task "aggregate_candle_1h"   "1h"   "1m"   "-1h5m"      "candle_1m"  "candle_1h"
create_ohlc_task "aggregate_candle_4h"   "4h"   "2m"   "-4h5m"      "candle_1h"  "candle_4h"
create_ohlc_task "aggregate_candle_1d"   "1d"   "2m"   "-1d5m"      "candle_1h"  "candle_1d"
create_ohlc_task "aggregate_candle_1w"   "1w"   "3m"   "-1w5m"      "candle_1d"  "candle_1w"   "4d"
create_ohlc_task "aggregate_candle_1M"   "1mo"  "3m"   "-32d"       "candle_1d"  "candle_1M"

echo "All InfluxDB aggregation tasks created."
