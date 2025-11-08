#!/bin/bash
# Setup Rust and cargo-ndk for Android development
# This script installs Rust and required tools for building Rust modules

set -e

echo "🔨 Setting up Rust for Android development..."

# Check if Rust is already installed
if command -v rustc &> /dev/null; then
    RUST_VERSION=$(rustc --version)
    echo "✅ Rust already installed: $RUST_VERSION"
else
    echo "⚠️  Rust not found. Installing Rust..."
    echo "📥 Downloading and installing Rust..."
    
    # Install rustup
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    
    # Add cargo to PATH for current session
    export PATH="$HOME/.cargo/bin:$PATH"
    
    # Verify installation
    if command -v cargo &> /dev/null; then
        echo "✅ Rust installation complete!"
    else
        echo "❌ Rust installation may have failed."
        echo "   Please restart your terminal and run this script again."
        echo "   Or install manually from: https://rustup.rs/"
        exit 1
    fi
fi

# Ensure cargo is in PATH
export PATH="$HOME/.cargo/bin:$PATH"

# Check if cargo-ndk is installed
if command -v cargo-ndk &> /dev/null; then
    NDK_VERSION=$(cargo-ndk --version)
    echo "✅ cargo-ndk already installed: $NDK_VERSION"
else
    echo "⚠️  cargo-ndk not found. Installing..."
    echo "📦 Installing cargo-ndk (this may take a few minutes)..."
    cargo install cargo-ndk
    
    if [ $? -eq 0 ]; then
        echo "✅ cargo-ndk installed successfully!"
    else
        echo "❌ Failed to install cargo-ndk"
        exit 1
    fi
fi

# Add Android targets
echo "📦 Adding Rust Android targets..."
TARGETS=(
    "aarch64-linux-android"
    "armv7-linux-androideabi"
    "x86_64-linux-android"
    "i686-linux-android"
)

for target in "${TARGETS[@]}"; do
    echo "   Adding target: $target"
    rustup target add "$target" || true
done

echo ""
echo "✅ Rust setup complete!"
echo "   You can now build the project with: ./gradlew assembleDebug"
echo ""
echo "⚠️  NOTE: You may need to restart your terminal for PATH changes to take effect."



