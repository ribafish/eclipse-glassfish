#!/bin/bash

# Get command from arguments
command="$1"

# Check if command is provided
if [ -z "$command" ]; then
  echo "Usage: $0 <command>"
  exit 1
fi

# Number of iterations
iterations=3

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

  time=$(echo "$output" | grep "\[INFO] Total time:")
  echo "Iteration $i: $time" >> times.txt
  scan=$(cat output.txt | grep "ge.solutions-team.gradle.com")
  echo "Iteration $i scan: $scan" >> times.txt
  rm output.txt

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
echo "Times files:"
cat times.txt

# Calculate average execution time
total=0
for time in "${times[@]}"; do
  total=$(echo "scale=4; $total + $time" | bc)
done
average=$(echo "scale=4; $total / $iterations" | bc)

printf "Average execution time: %.4f seconds\n" $average

echo "times=$(cat times.txt)" >> $GITHUB_OUTPUT
echo "average=Average $average" >> $GITHUB_OUTPUT

# Extract the total times from each line
times=$(grep -Eo '[0-9.:]+ (s|min)' times.txt)

# Convert the times to seconds
total_times=0
for time in $times; do
  if echo "$time" | grep -q ":"; then
    # Split the time into minutes and seconds
    minutes=$(echo "$time" | cut -d: -f1)
    seconds=$(echo "$time" | cut -d: -f2 | cut -d' ' -f1)  # Extract seconds, removing "min"
    # Convert minutes to seconds and add to seconds
    total_seconds=$(echo "scale=4; ($minutes * 60) + $seconds" | bc)
  else
    # Extract the seconds directly
    total_seconds=$(echo "$time" | sed 's/ s//')  # Remove " s"
  fi
  total_times=$(echo "scale=4; $total_times + $total_seconds" | bc)
done

# Calculate the average time
average_time=$(echo "scale=4; $total_times / ${#times[@]}" | bc)

# Format and display the average time
average_minutes=$(echo "$average_time / 60" | bc | cut -d '.' -f 1)  # Extract whole minutes
average_seconds=$(echo "$average_time % 60" | bc | cut -d '.' -f 1)  # Extract whole seconds
printf "Average time: %02d:%02d minutes\n" $average_minutes $average_seconds
echo "average2=Average2 $average_minutes:$average_seconds min" >> $GITHUB_OUTPUT

rm times.txt