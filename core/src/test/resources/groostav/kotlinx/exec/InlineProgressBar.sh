#!/usr/bin/env bash

echo "started Important task 42"

#taken from https://stackoverflow.com/a/39898465/1307108
duration=${1}

already_done() { for ((done=0; done<$elapsed; done++)); do printf "▇"; done }
remaining() { for ((remain=$elapsed; remain<$duration; remain++)); do printf " "; done }
percentage() { printf "| %s%%" $(( (($elapsed)*100)/($duration)*100/100 )); }
clean_line() { printf "\r"; }

for (( elapsed=1; elapsed<=$duration; elapsed++ )); do
  already_done; remaining; percentage
  sleep 0.1
  clean_line
done
clean_line

echo "finished Important task 42"