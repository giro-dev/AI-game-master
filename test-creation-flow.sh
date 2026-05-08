#!/bin/bash

# ═══════════════════════════════════════════════════════════════════════
# Comprehensive Test Suite for Character & Item Creation Flow
# Tests the new features: batch generation, validation endpoint,
# single generation, and enhanced item generation.
# ═══════════════════════════════════════════════════════════════════════

set -e

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
SKIP=0

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

pass() {
    PASS=$((PASS + 1))
    echo -e "${GREEN}  PASS${NC}: $1"
}

fail() {
    FAIL=$((FAIL + 1))
    echo -e "${RED}  FAIL${NC}: $1"
    if [ -n "$2" ]; then
        echo -e "        Detail: $2"
    fi
}

skip() {
    SKIP=$((SKIP + 1))
    echo -e "${YELLOW}  SKIP${NC}: $1"
}

section() {
    echo ""
    echo "═══════════════════════════════════════════════════════"
    echo "  $1"
    echo "═══════════════════════════════════════════════════════"
}

check_server() {
    curl -s --connect-timeout 3 "$BASE_URL" > /dev/null 2>&1
    return $?
}

# ── Blueprint used across tests ──
BLUEPRINT='{
  "systemId": "hitos",
  "actorType": "character",
  "actorFields": [
    {"path": "system.concepto", "type": "string", "label": "Concepto", "required": true},
    {"path": "system.biografia", "type": "string", "label": "Biografia", "required": false},
    {"path": "system.atributos.fuerza", "type": "number", "label": "Fuerza", "min": 1, "max": 12},
    {"path": "system.atributos.destreza", "type": "number", "label": "Destreza", "min": 1, "max": 12},
    {"path": "system.atributos.mente", "type": "number", "label": "Mente", "min": 1, "max": 12}
  ],
  "availableItems": [],
  "constraints": ["Attribute values must be between 1 and 12"],
  "coreFields": [
    {"key": "name", "path": "name", "type": "string", "label": "Name", "required": true}
  ],
  "example": {"actor": {"name": "Example", "type": "character"}, "items": []}
}'

# ═══════════════════════════════════════════════════════════════════════
echo ""
echo "Character & Item Creation Flow — Test Suite"
echo "Server: $BASE_URL"
echo "Date: $(date -u +"%Y-%m-%d %H:%M:%S UTC")"
echo ""

# ── Check server availability ──
if ! check_server; then
    echo -e "${YELLOW}Server not reachable at $BASE_URL — running offline tests only.${NC}"
    OFFLINE=true
else
    echo -e "${GREEN}Server reachable at $BASE_URL${NC}"
    OFFLINE=false
fi

# ═══════════════════════════════════════════════════════════════════════
section "1. VALIDATION ENDPOINT"
# ═══════════════════════════════════════════════════════════════════════

if [ "$OFFLINE" = true ]; then
    skip "Validation - valid data (server offline)"
    skip "Validation - missing required field (server offline)"
    skip "Validation - out of range value (server offline)"
else
    # Test 1.1: Valid character data
    VALIDATE_VALID='{
        "systemId": "hitos",
        "actorType": "character",
        "characterData": {
            "concepto": "A brave warrior",
            "atributos": {"fuerza": 8, "destreza": 6, "mente": 4}
        },
        "blueprint": '"$BLUEPRINT"'
    }'

    RESP=$(curl -s -X POST -H "Content-Type: application/json" -d "$VALIDATE_VALID" "$BASE_URL/gm/character/validate")
    VALID=$(echo "$RESP" | jq -r '.valid // empty')
    ERRORS=$(echo "$RESP" | jq -r '.errors | length')

    if [ "$VALID" = "true" ] && [ "$ERRORS" = "0" ]; then
        pass "Validation — valid data passes with no errors"
    else
        fail "Validation — valid data should pass" "valid=$VALID, errors=$ERRORS"
    fi

    # Test 1.2: Missing required field
    VALIDATE_MISSING='{
        "systemId": "hitos",
        "actorType": "character",
        "characterData": {
            "atributos": {"fuerza": 8}
        },
        "blueprint": '"$BLUEPRINT"'
    }'

    RESP=$(curl -s -X POST -H "Content-Type: application/json" -d "$VALIDATE_MISSING" "$BASE_URL/gm/character/validate")
    VALID=$(echo "$RESP" | jq -r '.valid // empty')
    ERRORS=$(echo "$RESP" | jq -r '.errors | length')

    if [ "$VALID" = "false" ] && [ "$ERRORS" -gt 0 ]; then
        pass "Validation — detects missing required field"
    else
        fail "Validation — should detect missing required field" "valid=$VALID, errors=$ERRORS"
    fi

    # Test 1.3: Out of range value
    VALIDATE_RANGE='{
        "systemId": "hitos",
        "actorType": "character",
        "characterData": {
            "concepto": "A warrior",
            "atributos": {"fuerza": 999, "destreza": 6, "mente": 4}
        },
        "blueprint": '"$BLUEPRINT"'
    }'

    RESP=$(curl -s -X POST -H "Content-Type: application/json" -d "$VALIDATE_RANGE" "$BASE_URL/gm/character/validate")
    ERRORS=$(echo "$RESP" | jq -r '.errors | length')

    if [ "$ERRORS" -gt 0 ]; then
        pass "Validation — detects out-of-range attribute"
    else
        fail "Validation — should detect value 999 > max 12"
    fi
fi

# ═══════════════════════════════════════════════════════════════════════
section "2. SINGLE CHARACTER GENERATION"
# ═══════════════════════════════════════════════════════════════════════

if [ "$OFFLINE" = true ]; then
    skip "Single generation (server offline)"
else
    SINGLE_REQ='{
        "prompt": "A cunning thief who operates in the shadows",
        "actorType": "character",
        "language": "en",
        "sessionId": "test-flow-single",
        "blueprint": '"$BLUEPRINT"'
    }'

    RESP=$(curl -s --max-time 60 -X POST -H "Content-Type: application/json" -d "$SINGLE_REQ" "$BASE_URL/gm/character/generate")
    SUCCESS=$(echo "$RESP" | jq -r '.success // empty')
    NAME=$(echo "$RESP" | jq -r '.character.actor.name // empty')

    if [ "$SUCCESS" = "true" ] && [ -n "$NAME" ]; then
        pass "Single generation — character created: $NAME"
    else
        fail "Single generation — expected success=true and non-empty name" "success=$SUCCESS, name=$NAME"
    fi

    # Verify nested fields were populated
    CONCEPTO=$(echo "$RESP" | jq -r '.character.actor.system.concepto // empty')
    if [ -n "$CONCEPTO" ]; then
        pass "Single generation — concepto field populated"
    else
        fail "Single generation — concepto field was empty"
    fi
fi

# ═══════════════════════════════════════════════════════════════════════
section "3. BATCH CHARACTER GENERATION"
# ═══════════════════════════════════════════════════════════════════════

if [ "$OFFLINE" = true ]; then
    skip "Batch generation (server offline)"
else
    BATCH_REQ='{
        "prompt": "Create diverse NPCs for a medieval tavern",
        "actorType": "character",
        "language": "en",
        "count": 2,
        "variationMode": "diverse",
        "sessionId": "test-flow-batch",
        "blueprint": '"$BLUEPRINT"'
    }'

    RESP=$(curl -s --max-time 120 -X POST -H "Content-Type: application/json" -d "$BATCH_REQ" "$BASE_URL/gm/character/generate/batch")
    SUCCESS=$(echo "$RESP" | jq -r '.success // empty')
    GENERATED=$(echo "$RESP" | jq -r '.generated // 0')
    REQUESTED=$(echo "$RESP" | jq -r '.requested // 0')
    CHAR_COUNT=$(echo "$RESP" | jq -r '.characters | length')

    if [ "$SUCCESS" = "true" ] && [ "$GENERATED" -gt 0 ]; then
        pass "Batch generation — generated $GENERATED of $REQUESTED characters"
    else
        fail "Batch generation — expected at least 1 character" "success=$SUCCESS, generated=$GENERATED"
    fi

    # Verify characters are distinct
    if [ "$CHAR_COUNT" -ge 2 ]; then
        NAME1=$(echo "$RESP" | jq -r '.characters[0].character.actor.name // empty')
        NAME2=$(echo "$RESP" | jq -r '.characters[1].character.actor.name // empty')
        if [ "$NAME1" != "$NAME2" ]; then
            pass "Batch generation — characters have distinct names: $NAME1 vs $NAME2"
        else
            fail "Batch generation — characters should have different names" "both=$NAME1"
        fi
    fi
fi

# ═══════════════════════════════════════════════════════════════════════
section "4. BATCH VALIDATION — BAD REQUESTS"
# ═══════════════════════════════════════════════════════════════════════

if [ "$OFFLINE" = true ]; then
    skip "Batch validation (server offline)"
else
    # Missing prompt
    BAD_REQ='{"actorType": "character", "count": 2, "blueprint": '"$BLUEPRINT"'}'
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" -d "$BAD_REQ" "$BASE_URL/gm/character/generate/batch")

    if [ "$HTTP_CODE" = "400" ]; then
        pass "Batch validation — rejects missing prompt (HTTP 400)"
    else
        fail "Batch validation — expected HTTP 400 for missing prompt" "got HTTP $HTTP_CODE"
    fi

    # Missing blueprint
    BAD_REQ2='{"prompt": "test", "actorType": "character", "count": 2}'
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" -d "$BAD_REQ2" "$BASE_URL/gm/character/generate/batch")

    if [ "$HTTP_CODE" = "400" ]; then
        pass "Batch validation — rejects missing blueprint (HTTP 400)"
    else
        fail "Batch validation — expected HTTP 400 for missing blueprint" "got HTTP $HTTP_CODE"
    fi
fi

# ═══════════════════════════════════════════════════════════════════════
section "5. REFERENCE CHARACTER API"
# ═══════════════════════════════════════════════════════════════════════

if [ "$OFFLINE" = true ]; then
    skip "Reference character store/retrieve (server offline)"
else
    REF_DATA='{
        "systemId": "hitos",
        "actorType": "character",
        "label": "Test Reference",
        "system": {"concepto": "Example"},
        "items": [{"name": "Skill A", "type": "skill", "system": {"value": 50}}]
    }'

    # Store
    STORE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" -d "$REF_DATA" "$BASE_URL/gm/character/reference")
    if [ "$STORE_CODE" = "200" ]; then
        pass "Reference character — stored successfully"
    else
        fail "Reference character — store failed" "HTTP $STORE_CODE"
    fi

    # Retrieve
    RESP=$(curl -s "$BASE_URL/gm/character/reference/hitos/character")
    LABEL=$(echo "$RESP" | jq -r '.label // empty')
    if [ "$LABEL" = "Test Reference" ]; then
        pass "Reference character — retrieved correctly"
    else
        fail "Reference character — retrieve failed" "label=$LABEL"
    fi

    # Delete
    DEL_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE_URL/gm/character/reference/hitos/character")
    if [ "$DEL_CODE" = "200" ]; then
        pass "Reference character — deleted successfully"
    else
        fail "Reference character — delete failed" "HTTP $DEL_CODE"
    fi
fi

# ═══════════════════════════════════════════════════════════════════════
section "RESULTS"
# ═══════════════════════════════════════════════════════════════════════

TOTAL=$((PASS + FAIL + SKIP))
echo ""
echo "Total: $TOTAL | Passed: $PASS | Failed: $FAIL | Skipped: $SKIP"
echo ""

if [ "$FAIL" -gt 0 ]; then
    echo -e "${RED}Some tests failed.${NC}"
    exit 1
fi

if [ "$PASS" -gt 0 ]; then
    echo -e "${GREEN}All executed tests passed.${NC}"
fi

exit 0
