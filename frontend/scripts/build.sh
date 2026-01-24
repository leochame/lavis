#!/bin/bash

# =============================================================================
# Lavis Build Script - 一键打包脚本
# =============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT_ROOT="$(dirname "$FRONTEND_DIR")"

JRE_VERSION="21"
ADOPTIUM_API="https://api.adoptium.net/v3/binary/latest/${JRE_VERSION}/ga"

detect_platform() {
    case "$(uname -s)" in
        Darwin*) echo "mac" ;;
        Linux*) echo "linux" ;;
        MINGW*|MSYS*|CYGWIN*) echo "win" ;;
        *) echo "unknown" ;;
    esac
}

detect_arch() {
    case "$(uname -m)" in
        x86_64|amd64) echo "x64" ;;
        arm64|aarch64) echo "arm64" ;;
        *) echo "x64" ;;
    esac
}

CURRENT_PLATFORM=$(detect_platform)
CURRENT_ARCH=$(detect_arch)
TARGET_PLATFORM="${1:-$CURRENT_PLATFORM}"

log_info "=========================================="
log_info "  Lavis Build Script"
log_info "=========================================="
log_info "Platform: $CURRENT_PLATFORM-$CURRENT_ARCH"
log_info "Target: $TARGET_PLATFORM"

# Build backend
build_backend() {
    log_info "Building Java backend..."
    cd "$PROJECT_ROOT"

    if [ -f "./mvnw" ]; then
        ./mvnw clean package -DskipTests -q
    else
        mvn clean package -DskipTests -q
    fi

    JAR_FILE=$(find target -maxdepth 1 -name "*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" 2>/dev/null | head -1)
    [ -z "$JAR_FILE" ] && { log_error "JAR not found"; exit 1; }

    cp "$JAR_FILE" "target/lavis.jar"
    log_success "Backend built"
}

# Download JRE
download_jre() {
    local os=$1 arch=$2
    local target_dir="$FRONTEND_DIR/jre/${os}-${arch}"

    [ -d "$target_dir" ] && [ "$(ls -A "$target_dir" 2>/dev/null)" ] && {
        log_info "JRE exists for ${os}-${arch}"
        return 0
    }

    local api_os=$os api_arch=$arch
    [ "$os" = "win" ] && api_os="windows"
    [ "$arch" = "arm64" ] && api_arch="aarch64"

    log_info "Downloading JRE for ${os}-${arch}..."
    mkdir -p "$target_dir"

    local url="${ADOPTIUM_API}/${api_os}/${api_arch}/jre/hotspot/normal/eclipse"
    local tmp="/tmp/jre-${os}-${arch}"
    [ "$os" = "win" ] && tmp="${tmp}.zip" || tmp="${tmp}.tar.gz"

    curl -L -o "$tmp" "$url" --progress-bar || { log_error "Download failed"; return 1; }

    if [ "$os" = "win" ]; then
        unzip -q "$tmp" -d "/tmp/jre-ext"
        mv /tmp/jre-ext/*/* "$target_dir/" 2>/dev/null || true
        rm -rf /tmp/jre-ext
    else
        tar -xzf "$tmp" -C "$target_dir" --strip-components=1
    fi

    rm -f "$tmp"
    log_success "JRE ready for ${os}-${arch}"
}

# Prepare JRE
prepare_jre() {
    log_info "Preparing JRE..."
    case "$TARGET_PLATFORM" in
        mac) download_jre "mac" "$CURRENT_ARCH" ;;
        win) download_jre "win" "x64" ;;
        linux) download_jre "linux" "x64" ;;
        all)
            download_jre "mac" "arm64"
            download_jre "mac" "x64"
            download_jre "win" "x64"
            download_jre "linux" "x64"
            ;;
    esac
}

# Build frontend
build_frontend() {
    log_info "Building frontend..."
    cd "$FRONTEND_DIR"

    [ ! -d "node_modules" ] && npm install --legacy-peer-deps

    npm run build
    npm run build:electron
    log_success "Frontend built"
}

# Package
package_electron() {
    log_info "Packaging..."
    cd "$FRONTEND_DIR"

    case "$TARGET_PLATFORM" in
        mac) npx electron-builder --mac --$CURRENT_ARCH ;;
        win) npx electron-builder --win ;;
        linux) npx electron-builder --linux ;;
        all) npx electron-builder --mac --win --linux ;;
    esac

    log_success "Package complete"
}

# Main
main() {
    local start=$(date +%s)

    build_backend
    prepare_jre
    build_frontend
    package_electron

    local duration=$(($(date +%s) - start))

    log_success "=========================================="
    log_success "  Build Complete! (${duration}s)"
    log_success "=========================================="
    log_info "Output: $FRONTEND_DIR/dist-electron"
    ls -lh "$FRONTEND_DIR/dist-electron"/*.{dmg,exe,AppImage,deb} 2>/dev/null || true
}

main
