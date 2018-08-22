#!/usr/bin/env bash

prefix="nginx-proxy  |"
PID=$$

echo "$prefix starting worker-2 with PID=$PID"

echo "$prefix starting proxy"
sleep 0.8
echo "$prefix HTTP GET /acme/challenge 200 OK"
echo "$prefix HTTP GET /acme/challenge/abcd1234 200 OK"
sleep 0.1
echo "$prefix got Lets encrypt certificate"

while true; do
    echo "$prefix HTTP GET /imporant/customers 200 OK"
    sleep 0.6
done

echo "$prefix exiting"
