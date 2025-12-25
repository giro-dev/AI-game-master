#!/bin/bash
# Script per executar Foundry VTT en mode desenvolupament
# Inclou auto-reload del mòdul quan detecta canvis

# Configuració
FOUNDRY_DIR="/home/albert/app/FoundryVTT-12.343"
FOUNDRY_DATA="/home/albert/.local/share/FoundryVTT"
MODULE_SOURCE="/home/albert/lab/game-master/master-foundry"
MODULE_TARGET="$FOUNDRY_DATA/Data/modules/ai-gm"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}[Foundry Dev Launcher]${NC} Starting Foundry VTT in development mode..."

# Verificar directori de Foundry
if [ ! -d "$FOUNDRY_DIR" ]; then
    echo -e "${RED}[Error]${NC} Foundry directory not found: $FOUNDRY_DIR"
    exit 1
fi

# Crear enllaç simbòlic del mòdul si no existeix
if [ ! -L "$MODULE_TARGET" ]; then
    echo -e "${YELLOW}[Dev Setup]${NC} Creating symlink for module..."
    mkdir -p "$FOUNDRY_DATA/Data/modules"
    ln -s "$MODULE_SOURCE" "$MODULE_TARGET"
    echo -e "${GREEN}[Dev Setup]${NC} Symlink created: $MODULE_TARGET -> $MODULE_SOURCE"
else
    echo -e "${GREEN}[Dev Setup]${NC} Module symlink already exists"
fi

# Exportar variables d'entorn
export FOUNDRY_VTT_DATA_PATH="$FOUNDRY_DATA"
export NODE_ENV="development"

# Executar Foundry
cd "$FOUNDRY_DIR/resources/app"

echo -e "${GREEN}[Foundry Dev]${NC} Launching..."
echo -e "${GREEN}[Foundry Dev]${NC} Access at: http://localhost:30000"
echo -e "${YELLOW}[Foundry Dev]${NC} Module changes will be reflected (may need F5)"
echo ""

node main.js

echo -e "${BLUE}[Foundry Dev]${NC} Foundry VTT stopped"

