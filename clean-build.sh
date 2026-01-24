#!/bin/bash

# =============================================================================
# Lavis Build Cleanup Script - 清理所有打包产物和进程
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
FRONTEND_DIR="$SCRIPT_DIR/frontend"
PROJECT_ROOT="$SCRIPT_DIR"

log_info "=========================================="
log_info "  Lavis Build Cleanup"
log_info "=========================================="

# 1. 关闭所有Lavis相关进程
log_info "Stopping Lavis processes..."
pkill -f "lavis" 2>/dev/null && log_success "Lavis processes stopped" || log_info "No Lavis processes found"
pkill -f "electron.*lavis" 2>/dev/null && log_success "Electron Lavis processes stopped" || log_info "No Electron Lavis processes found"
ps aux | grep -E "(java.*lavis|LavisApplication)" | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null && log_success "Java Lavis processes stopped" || log_info "No Java Lavis processes found"

# 2. 删除打包产物目录
log_info "Removing build directories..."

# Frontend build directories
[ -d "$FRONTEND_DIR/dist-electron" ] && rm -rf "$FRONTEND_DIR/dist-electron" && log_success "Removed frontend/dist-electron"
[ -d "$FRONTEND_DIR/jre" ] && rm -rf "$FRONTEND_DIR/jre" && log_success "Removed frontend/jre"
[ -d "$FRONTEND_DIR/dist" ] && rm -rf "$FRONTEND_DIR/dist" && log_success "Removed frontend/dist"
[ -d "$FRONTEND_DIR/build" ] && rm -rf "$FRONTEND_DIR/build" && log_success "Removed frontend/build"

# Backend build directories
[ -d "$PROJECT_ROOT/target" ] && rm -rf "$PROJECT_ROOT/target" && log_success "Removed target/"

# 3. 删除打包生成的APP文件
log_info "Removing packaged applications..."

find "$PROJECT_ROOT" -type f \( -name "*.dmg" -o -name "*.app" -o -name "*.exe" -o -name "*.AppImage" -o -name "*.deb" -o -name "*.rpm" \) -not -path "*/node_modules/*" -not -path "*/.git/*" -exec rm -f {} \; 2>/dev/null && log_success "Removed packaged application files" || log_info "No packaged application files found"

# 4. 删除JAR文件（保留源代码）
log_info "Removing JAR files..."
find "$PROJECT_ROOT" -type f -name "*.jar" -not -path "*/node_modules/*" -not -path "*/.git/*" -exec rm -f {} \; 2>/dev/null && log_success "Removed JAR files" || log_info "No JAR files found"

# 5. 清理临时文件
log_info "Cleaning temporary files..."
find /tmp -maxdepth 1 -name "*jre*" -type d -exec rm -rf {} \; 2>/dev/null && log_success "Removed temporary JRE directories" || log_info "No temporary JRE directories found"
find /tmp -maxdepth 1 -name "*jre*" -type f -exec rm -f {} \; 2>/dev/null && log_success "Removed temporary JRE files" || log_info "No temporary JRE files found"
find /tmp -maxdepth 1 -name "*lavis*" -exec rm -rf {} \; 2>/dev/null && log_success "Removed temporary Lavis files" || log_info "No temporary Lavis files found"

# 6. 检查Applications目录中的Lavis应用
log_info "Checking Applications directory..."
if [ -d "/Applications" ]; then
    find /Applications -maxdepth 2 -name "*Lavis*" -o -name "*lavis*" 2>/dev/null | while read app; do
        if [ -e "$app" ]; then
            log_warn "Found application: $app"
            read -p "Delete this application? (y/N): " -n 1 -r
            echo
            if [[ $REPLY =~ ^[Yy]$ ]]; then
                rm -rf "$app" && log_success "Removed $app"
            fi
        fi
    done
fi

log_success "=========================================="
log_success "  Cleanup Complete!"
log_success "=========================================="

