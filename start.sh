#!/usr/bin/env bash
# ============================================================
#  Lavis - One-click Development Launcher
#  Usage:  ./start.sh
#
#  This script will:
#   0. Install Homebrew if not present (needed for auto-install)
#   1. Check & auto-install prerequisites (JDK 21+, Node.js 18+)
#   2. Ensure .env is configured
#   3. Install frontend dependencies if needed
#   4. Start the Spring Boot backend
#   5. Start the Electron + Vite frontend
#   6. Clean up all child processes on exit
# ============================================================

set -euo pipefail

# ------ Color helpers ------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

# ------ Resolve project root (where this script lives) ------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ------ Cleanup on exit: kill all background children ------
BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
    echo ""
    info "Shutting down Lavis..."

    if [[ -n "$FRONTEND_PID" ]] && kill -0 "$FRONTEND_PID" 2>/dev/null; then
        kill -TERM "$FRONTEND_PID" 2>/dev/null
        wait "$FRONTEND_PID" 2>/dev/null || true
        ok "Frontend stopped."
    fi

    if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" 2>/dev/null; then
        kill -TERM "$BACKEND_PID" 2>/dev/null
        wait "$BACKEND_PID" 2>/dev/null || true
        ok "Backend stopped."
    fi

    # Also kill any lingering child processes in our process group
    kill -- -$$ 2>/dev/null || true

    ok "Lavis shut down completely."
}
trap cleanup EXIT INT TERM

# ============================================================
#  0. Ensure Homebrew is available (needed for auto-install)
# ============================================================
ensure_brew() {
    if command -v brew &>/dev/null; then
        return 0
    fi
    info "Homebrew not found. Installing Homebrew..."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    # Add brew to PATH for Apple Silicon and Intel Macs
    if [[ -f /opt/homebrew/bin/brew ]]; then
        eval "$(/opt/homebrew/bin/brew shellenv)"
    elif [[ -f /usr/local/bin/brew ]]; then
        eval "$(/usr/local/bin/brew shellenv)"
    fi
    if command -v brew &>/dev/null; then
        ok "Homebrew installed."
    else
        error "Homebrew installation failed. Please install manually: https://brew.sh"
        exit 1
    fi
}

# ============================================================
#  1. Prerequisites check & auto-install
# ============================================================
info "Checking prerequisites..."

# --- Java ---
NEED_JAVA=false
if ! command -v java &>/dev/null; then
    NEED_JAVA=true
else
    JAVA_VER=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)\..*/\1/')
    if [[ "$JAVA_VER" -lt 21 ]]; then
        warn "Java $JAVA_VER found, but 21+ is required."
        NEED_JAVA=true
    fi
fi

if [[ "$NEED_JAVA" == "true" ]]; then
    info "Installing JDK 21 via Homebrew..."
    ensure_brew
    brew install openjdk@21
    # Symlink so system java points to the new version
    sudo ln -sfn "$(brew --prefix openjdk@21)/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-21.jdk 2>/dev/null || true
    export PATH="$(brew --prefix openjdk@21)/bin:$PATH"
    if ! command -v java &>/dev/null; then
        error "JDK 21 installation failed. Please install manually: brew install openjdk@21"
        exit 1
    fi
    ok "JDK 21 installed."
fi

JAVA_VER=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)\..*/\1/')
ok "Java $JAVA_VER"

# --- Node.js ---
NEED_NODE=false
if ! command -v node &>/dev/null; then
    NEED_NODE=true
else
    NODE_VER=$(node -v | sed 's/v//' | cut -d. -f1)
    if [[ "$NODE_VER" -lt 18 ]]; then
        warn "Node.js $NODE_VER found, but 18+ is required."
        NEED_NODE=true
    fi
fi

if [[ "$NEED_NODE" == "true" ]]; then
    info "Installing Node.js via Homebrew..."
    ensure_brew
    brew install node
    if ! command -v node &>/dev/null; then
        error "Node.js installation failed. Please install manually: brew install node"
        exit 1
    fi
    ok "Node.js installed."
fi

ok "Node.js $(node -v)"

# --- npm (comes with Node.js, just verify) ---
if ! command -v npm &>/dev/null; then
    error "npm not found. This is unexpected if Node.js is installed."
    error "Try reinstalling Node.js: brew reinstall node"
    exit 1
fi
ok "npm $(npm -v)"

# ============================================================
#  2. Environment configuration
# ============================================================
if [[ ! -f .env ]]; then
    if [[ -f .env.example ]]; then
        warn ".env file not found. Creating from .env.example..."
        cp .env.example .env
        warn "Please edit .env and fill in your API keys, then re-run this script."
        echo ""
        warn "Required keys:"
        warn "  - app.llm.models.fast-model.api-key  (Gemini API key)"
        warn "  - app.llm.models.whisper.api-key      (STT API key)"
        warn "  - app.llm.models.tts.api-key           (TTS API key)"
        echo ""
        exit 1
    else
        error ".env and .env.example not found. Cannot configure the application."
        exit 1
    fi
fi
ok ".env file exists"

# Quick check if API keys are filled in
if grep -q '^app\.llm\.models\.fast-model\.api-key=$' .env 2>/dev/null; then
    warn "It looks like the Chat API key in .env is empty."
    warn "Lavis may not work properly without API keys."
    warn "Press Enter to continue anyway, or Ctrl+C to abort..."
    read -r
fi

# ============================================================
#  3. Install frontend dependencies
# ============================================================
if [[ ! -d frontend/node_modules ]]; then
    info "Installing frontend dependencies (npm install)..."
    (cd frontend && npm install)
    ok "Frontend dependencies installed."
else
    ok "Frontend dependencies already installed."
fi

# ============================================================
#  4. Start Backend (Spring Boot)
# ============================================================
echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Starting Lavis...${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

info "Starting backend (Spring Boot on port 18765)..."
./mvnw spring-boot:run -q &
BACKEND_PID=$!

# Wait for backend to be ready
info "Waiting for backend to be ready..."
BACKEND_READY=false
for i in $(seq 1 60); do
    if curl -s -o /dev/null -w '' http://localhost:18765/actuator/health 2>/dev/null || \
       curl -s -o /dev/null -w '' http://localhost:18765 2>/dev/null; then
        BACKEND_READY=true
        break
    fi

    # Check if the process is still alive
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
        error "Backend process exited unexpectedly. Check the logs above."
        exit 1
    fi

    sleep 2
done

if [[ "$BACKEND_READY" == "true" ]]; then
    ok "Backend is ready!"
else
    warn "Backend did not respond within 120s, but may still be starting..."
    warn "Continuing to start frontend anyway..."
fi

# ============================================================
#  5. Start Frontend (Electron + Vite)
# ============================================================
info "Starting frontend (Electron + Vite)..."
(cd frontend && npm run electron:dev) &
FRONTEND_PID=$!

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Lavis is running!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "  Backend:  ${CYAN}http://localhost:18765${NC}"
echo -e "  Frontend: ${CYAN}Electron desktop app${NC}"
echo ""
echo -e "  Press ${YELLOW}Ctrl+C${NC} to stop all services."
echo ""

# Wait for either process to exit
wait -n "$BACKEND_PID" "$FRONTEND_PID" 2>/dev/null || true
