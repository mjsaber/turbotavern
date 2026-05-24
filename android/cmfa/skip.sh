#!/bin/bash
TARGET_ID=$(curl -s http://127.0.0.1:9091/connections | jq -r '.connections[] | select(.metadata.process == "com.blizzard.wtcg.hearthstone" and .metadata.host == "") | .id' | head -1)
if [ -z "$TARGET_ID" ]; then
  echo "no host=='' HS connection found"
  exit 1
fi
echo "killing $TARGET_ID"
curl -s -X DELETE "http://127.0.0.1:9091/connections/$TARGET_ID" -w "HTTP %{http_code}\n"
