#!/bin/bash

set -x

# Get command from arguments
command="$1"

# Check if command is provided
if [ -z "$command" ]; then
  echo "Usage: $0 <command>"
  exit 1
fi

rm times.txt || :

# Number of iterations
iterations=2

# Array to store execution times
times=()

# Loop through iterations
for i in $(seq 1 $iterations); do
  rm output.txt || :
  eval "$command" | tee output.txt

  time=$(grep "\[INFO] Total time:" output.txt)
  echo "Iteration $i: $time" >> times.txt
  scan=$(grep "ge.solutions-team.gradle.com" output.txt)
  echo "Iteration $i scan: $scan" >> times.txt

  # Convert minutes to seconds if necessary
  if echo "$time" | grep -q "min"; then
    time=$(echo "$time" | sed -E 's/.*: +([0-9:]+) +min.*/\1/')
    minutes=$(echo "$time" | cut -d: -f1 | bc)
    seconds=$(echo "$time" | cut -d: -f2 | bc)
    time=$(echo "scale=4; (${minutes:-0} * 60) + ${seconds:-0}" | bc)
  else
    time=$(echo "$time" | sed -E 's/.*: +([0-9.]+) +s.*/\1/')
  fi

  times+=("$time")
  printf "Iteration %d: %s seconds\n" $i $time
done

# Print the times array
echo "Times array: ${times[@]}"
echo "Times files:"
cat times.txt

# Calculate average execution time
total=0
for time in "${times[@]}"; do
  total=$(echo "scale=4; $total + $time" | bc)
done
average=$(echo "scale=4; $total / $iterations" | bc)

printf "Average execution time: %.4f seconds\n" $average

echo "times=Times array: ${times[@]}">> $GITHUB_OUTPUT
echo "average=Average: $average seconds" >> $GITHUB_OUTPUT
