#!/usr/bin/env bash

PID=$$

parent="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

echo "parent is starting in $parent PID=$PID"

bash "$parent/Worker1.sh" &
bash "$parent/Worker2.sh" &

while true; do
    sleep 0.5
done