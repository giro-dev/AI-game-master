#!/bin/bash

# Define OpenSearch endpoint
OPENSEARCH_URL="http://localhost:9200"

# Create indices
indices=("cache" "session_data" "transcriptions")

for index in "${indices[@]}"; do
  echo "Creating index: $index"
  curl -X PUT "$OPENSEARCH_URL/$index" -H 'Content-Type: application/json' -d'{
    "settings": {
      "index": {
        "number_of_shards": 1,
        "number_of_replicas": 0
      }
    }
  }'
  echo "\n"
done

# Verify indices
curl -X GET "$OPENSEARCH_URL/_cat/indices?v"
