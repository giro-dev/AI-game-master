#!/bin/bash

# Test script for character generation improvements

echo "=== Testing Character Generation Improvements ==="
echo ""

# Test data - vehicle request (as per user's example)
REQUEST_DATA='{
  "prompt": "Una ambulancia vieja y atrotinada",
  "actorType": "vehicle",
  "language": "ca",
  "sessionId": "test-session-123",
  "blueprint": {
    "systemId": "hitos",
    "actorType": "vehicle",
    "actorFields": [
      {
        "path": "system.concepto",
        "type": "string",
        "label": "Concepto",
        "default": ""
      },
      {
        "path": "system.biografia",
        "type": "string",
        "label": "Biografia",
        "default": ""
      },
      {
        "path": "system.atributos.est",
        "type": "resource",
        "label": "Est",
        "default": 0,
        "min": 0,
        "max": 12
      },
      {
        "path": "system.atributos.tam",
        "type": "resource",
        "label": "Tam",
        "default": 0,
        "min": 0,
        "max": 12
      },
      {
        "path": "system.caracteristicas.blindaje",
        "type": "number",
        "label": "Blindaje",
        "default": 0
      },
      {
        "path": "system.caracteristicas.defensa",
        "type": "number",
        "label": "Defensa",
        "default": 0
      }
    ],
    "availableItems": [],
    "constraints": [],
    "coreFields": [
      {
        "key": "name",
        "path": "name",
        "type": "string",
        "label": "Name",
        "required": true
      },
      {
        "key": "img",
        "path": "img",
        "type": "string",
        "label": "Portrait Image",
        "required": false,
        "default": "icons/svg/mystery-man.svg"
      }
    ],
    "example": {
      "actor": {
        "name": "Example Vehicle",
        "type": "vehicle",
        "system": {
          "concepto": "",
          "biografia": ""
        }
      },
      "items": []
    }
  }
}'

echo "Sending test request to http://localhost:8080/gm/character/generate"
echo ""

# Send request and save response
RESPONSE=$(curl -s -X POST \
  -H "Content-Type: application/json" \
  -d "$REQUEST_DATA" \
  http://localhost:8080/gm/character/generate)

echo "Response:"
echo "$RESPONSE" | jq '.'
echo ""

# Check if all fields were filled
echo "=== Verification ==="
echo ""

# Check success
SUCCESS=$(echo "$RESPONSE" | jq -r '.success')
echo "1. Success: $SUCCESS"

# Check if concepto is filled
CONCEPTO=$(echo "$RESPONSE" | jq -r '.character.actor.system.concepto // empty')
echo "2. Concepto filled: ${CONCEPTO:0:50}..."

# Check if biografia is filled
BIOGRAFIA=$(echo "$RESPONSE" | jq -r '.character.actor.system.biografia // empty')
echo "3. Biografia filled: ${BIOGRAFIA:0:50}..."

# Check if nested atributos are filled
EST=$(echo "$RESPONSE" | jq -r '.character.actor.system.atributos.est // empty')
TAM=$(echo "$RESPONSE" | jq -r '.character.actor.system.atributos.tam // empty')
echo "4. Atributos.est filled: $EST"
echo "5. Atributos.tam filled: $TAM"

# Check if nested caracteristicas are filled
BLINDAJE=$(echo "$RESPONSE" | jq -r '.character.actor.system.caracteristicas.blindaje // empty')
DEFENSA=$(echo "$RESPONSE" | jq -r '.character.actor.system.caracteristicas.defensa // empty')
echo "6. Caracteristicas.blindaje filled: $BLINDAJE"
echo "7. Caracteristicas.defensa filled: $DEFENSA"

echo ""
echo "=== Test Complete ==="

# Exit with error code if not successful
if [ "$SUCCESS" != "true" ]; then
    echo "ERROR: Character generation was not successful"
    exit 1
fi

# Check if critical fields are empty
if [ -z "$CONCEPTO" ] || [ -z "$BIOGRAFIA" ] || [ -z "$EST" ] || [ -z "$TAM" ]; then
    echo "WARNING: Some critical fields were not filled"
    exit 1
fi

echo "SUCCESS: All critical fields were filled correctly"
exit 0

