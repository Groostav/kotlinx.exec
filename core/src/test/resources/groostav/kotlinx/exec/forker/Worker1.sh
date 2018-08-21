#!/usr/bin/env bash

prefix="worker1      |"

PID=$$
echo "$prefix worker-1 with PID=$PID"

while true; do

    echo "$prefix squeezing limes"
    sleep 0.8
    echo "$prefix provisioning mint"
    sleep 0.4
    echo "$prefix sourcing white rum"
    sleep 0.5
    echo "$prefix successfully acquired mojito"
    sleep 0.6
done

echo "$prefix exiting"
