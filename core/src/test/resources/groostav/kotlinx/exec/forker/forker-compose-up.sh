#!/usr/bin/env bash


parent = "$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

Write-Output "parent is starting in $parent PID=$PID"

bash "$parent/Worker1.sh"
bash "$parent/Worker2.sh"

while($true) { Sleep -m 5000 }