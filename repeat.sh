#!/bin/bash

# Get command from arguments
command="$1"

# Check if command is provided
if [ -z "$command" ]; then
  echo "Usage: $0 <command>"
  exit 1
fi

# Number of iterations
iterations=20

# Array to store execution times
times=()

# Loop through iterations
for i in $(seq 1 $iterations); do
  # Execute the command and capture output
  # output=$(eval "$command")
  # Execute the command, capture output, and display it in real-time
#  output=$(eval "$command" | tee /dev/tty | grep "\[INFO] Total time:" | sed -E 's/.*:\s+([0-9.:]+)\s+(s|min).*$/\1/')
  eval "$command" | tee output.txt
  output=$(cat output.txt | grep "\[INFO] Total time:" | sed -E 's/.*:\s+([0-9.:]+)\s+(s|min).*$/\1/')
  rm output.txt

  time=$(echo "$output" | grep "\[INFO] Total time:")

  # Convert minutes to seconds if necessary
  if echo "$time" | grep -q "min"; then
    time=$(echo "$time" | sed -E 's/.*: +([0-9:]+) +min.*/\1/')
    minutes=$(echo "$time" | cut -d: -f1 | bc)
    seconds=$(echo "$time" | cut -d: -f2 | bc)
    time=$(echo "scale=4; (${minutes:-0} * 60) + ${seconds:-0}" | bc)
  else
    time=$(echo "$time" | sed -E 's/.*: +([0-9.]+) s/\1/')
  fi

  times+=("$time")
  printf "Iteration %d: %s seconds\n" $i $time
done

# Print the times array
echo "Times array: ${times[@]}"

# Calculate average execution time
total=0
for time in "${times[@]}"; do
  total=$(echo "scale=4; $total + $time" | bc)
done
average=$(echo "scale=4; $total / $iterations" | bc)

printf "Average execution time: %.4f seconds\n" $average