#!/bin/bash
# Identify flaky tests in GitHub Actions workflow runs.
# Requires: gh CLI authenticated

LIMIT=${1:-10}
WORKFLOW="build.yml"
BRANCH="master"
REPO="square/okhttp"
SKILL_DIR=$(dirname "$0")
FAILURES_FILE=$(mktemp)
OUTPUT_FILE="$SKILL_DIR/flaky-tests.txt"

# Clear previous output
rm -f "$OUTPUT_FILE"
touch "$OUTPUT_FILE"

echo "Fetching last $LIMIT failed runs for $WORKFLOW on $BRANCH..."

RUN_IDS=$(gh run list --workflow "$WORKFLOW" --branch "$BRANCH" --status failure --limit "$LIMIT" --repo "$REPO" --json databaseId --jq '.[].databaseId')

if [ -z "$RUN_IDS" ]; then
  echo "No failed runs found."
  rm -f "$FAILURES_FILE"
  exit 0
fi

for run_id in $RUN_IDS; do
  echo "--------------------------------------------------------------------------------"
  echo "Run ID: $run_id"
  echo "URL: https://github.com/$REPO/actions/runs/$run_id"
  
  # Get failed job IDs
  JOB_DATA=$(gh api "repos/$REPO/actions/runs/$run_id/jobs" --jq '.jobs[] | select(.conclusion=="failure") | "\(.id) \(.name)"')
  
  if [ -z "$JOB_DATA" ]; then
    echo "  No failed jobs found (possibly cancelled or infra failure)."
    continue
  fi

  while read -r job_id job_name; do
    echo "  Job: $job_name (ID: $job_id)"
    # Fetch logs
    LOG_CONTENT=$(gh api "repos/$REPO/actions/jobs/$job_id/logs")
    
    # Extract failure details for display
    echo "$LOG_CONTENT" | grep "FAILED" -A 1 | grep -v "Task :" | sed 's/^/    /' || echo "    Could not extract failure details from logs."

    # Extract class names for summary
    echo "$LOG_CONTENT" | grep "FAILED" | grep -v "Task :" | grep -v "BUILD FAILED" | \
      sed -E 's/^.*Z //;s/^[[:space:]]*//;s/\[.*//;s/ >.*//' >> "$FAILURES_FILE"

    # Extract clean test names for output file (ClassName.methodName)
    # Filter out Android tests and malformed lines
    echo "$LOG_CONTENT" | grep "FAILED" | grep " > " | grep -v "Task :" | grep -v "android" | \
      sed -E 's/^.*Z //;s/\[.*\] > /./;s/\(.*//;s/_[0-9]+$//' >> "$OUTPUT_FILE"

  done <<< "$JOB_DATA"
done

echo ""
echo "========================================"
echo "SUMMARY OF FAILURES PER CLASS"
echo "========================================"
if [ -s "$FAILURES_FILE" ]; then
  sort "$FAILURES_FILE" | uniq -c | sort -nr
else
  echo "No specific test failures identified."
fi

# Unique and sort the output file
if [ -s "$OUTPUT_FILE" ]; then
  sort -u "$OUTPUT_FILE" -o "$OUTPUT_FILE"
  echo ""
  echo "Clean list of flaky tests written to: $OUTPUT_FILE"
else
  echo "No clean test names extracted."
fi

rm -f "$FAILURES_FILE"
