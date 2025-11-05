#!/usr/bin/env python3
"""
BoringSSL build script for all Android ABIs
Builds static libraries for arm64-v8a, armeabi-v7a, x86_64, x86
"""

import os
import sys
import subprocess
import shutil
from pathlib import Path

# Configuration
ABIS = {
    'arm64-v8a': {
        'arch': 'aarch64',
        'api_level': '21',
        'cmake_flags': [
            '-DCMAKE_ANDROID_ARCH_ABI=arm64-v8a',
            '-DCMAKE_SYSTEM_PROCESSOR=aarch64',
            '-DOPENSSL_NO_ASM=0',  # Enable assembly optimizations
        ]
    },
    'armeabi-v7a': {
        'arch': 'arm',
        'api_level': '21',
        'cmake_flags': [
            '-DCMAKE_ANDROID_ARCH_ABI=armeabi-v7a',
            '-DCMAKE_SYSTEM_PROCESSOR=armv7-a',
            '-DCMAKE_ANDROID_ARM_MODE=ON',
            '-DCMAKE_ANDROID_ARM_NEON=ON',
            '-DOPENSSL_NO_ASM=0',
        ]
    },
    'x86_64': {
        'arch': 'x86_64',
        'api_level': '21',
        'cmake_flags': [
            '-DCMAKE_ANDROID_ARCH_ABI=x86_64',
            '-DCMAKE_SYSTEM_PROCESSOR=x86_64',
            '-DOPENSSL_NO_ASM=0',
        ]
    },
    'x86': {
        'arch': 'i686',
        'api_level': '21',
        'cmake_flags': [
            '-DCMAKE_ANDROID_ARCH_ABI=x86',
            '-DCMAKE_SYSTEM_PROCESSOR=i686',
            '-DOPENSSL_NO_ASM=0',
        ]
    }
}

SCRIPT_DIR = Path(__file__).parent.absolute()
BORINGSSL_SRC = SCRIPT_DIR / 'src'
BUILD_DIR = SCRIPT_DIR / 'build'
LIB_DIR = SCRIPT_DIR / 'lib'
INCLUDE_DIR = SCRIPT_DIR / 'include'

def find_ndk():
    """Find Android NDK path from environment or common locations"""
    ndk_path = os.environ.get('ANDROID_NDK_HOME')
    if ndk_path and Path(ndk_path).exists():
        return Path(ndk_path)

    ndk_path = os.environ.get('NDK_HOME')
    if ndk_path and Path(ndk_path).exists():
        return Path(ndk_path)

    # Check common locations
    home = Path.home()
    candidates = [
        home / 'Android/Sdk/ndk-bundle',
        home / 'Library/Android/sdk/ndk-bundle',
        Path('/opt/android-ndk'),
    ]

    # Also check versioned NDK directories
    sdk_ndk = home / 'Android/Sdk/ndk'
    if sdk_ndk.exists():
        versions = sorted(sdk_ndk.iterdir(), reverse=True)
        if versions:
            return versions[0]

    for candidate in candidates:
        if candidate.exists():
            return candidate

    print("ERROR: Android NDK not found!")
    print("Please set ANDROID_NDK_HOME or NDK_HOME environment variable")
    sys.exit(1)

def clone_boringssl():
    """Clone BoringSSL repository if not present"""
    if BORINGSSL_SRC.exists():
        print(f"✓ BoringSSL source already exists at {BORINGSSL_SRC}")
        # Get current commit hash
        try:
            result = subprocess.run(
                ['git', 'rev-parse', 'HEAD'],
                cwd=BORINGSSL_SRC,
                capture_output=True,
                text=True,
                check=True
            )
            commit_hash = result.stdout.strip()[:12]
            print(f"  Current commit: {commit_hash}")
        except subprocess.CalledProcessError:
            print("  Warning: Could not get commit hash")
        return

    print("⚙ Cloning BoringSSL repository...")
    cmd = [
        'git', 'clone', '--depth=1',
        'https://boringssl.googlesource.com/boringssl',
        str(BORINGSSL_SRC)
    ]
    subprocess.run(cmd, check=True)

    # Print commit hash
    try:
        result = subprocess.run(
            ['git', 'rev-parse', 'HEAD'],
            cwd=BORINGSSL_SRC,
            capture_output=True,
            text=True,
            check=True
        )
        commit_hash = result.stdout.strip()[:12]
        print(f"✓ BoringSSL cloned successfully (commit: {commit_hash})")
    except subprocess.CalledProcessError:
        print("✓ BoringSSL cloned successfully")

def build_abi(abi_name, abi_config, ndk_path):
    """Build BoringSSL for a specific ABI"""
    print(f"\n{'='*60}")
    print(f"Building BoringSSL for {abi_name}")
    print(f"{'='*60}")

    build_dir = BUILD_DIR / abi_name
    # Clean build directory to prevent cross-contamination
    if build_dir.exists():
        print(f"🧹 Cleaning previous build for {abi_name}...")
        shutil.rmtree(build_dir)
    build_dir.mkdir(parents=True, exist_ok=True)

    toolchain_file = ndk_path / 'build/cmake/android.toolchain.cmake'
    if not toolchain_file.exists():
        print(f"ERROR: NDK toolchain file not found at {toolchain_file}")
        sys.exit(1)

    cmake_args = [
        'cmake',
        '-GNinja',
        f'-DCMAKE_TOOLCHAIN_FILE={toolchain_file}',
        f'-DCMAKE_ANDROID_NDK={ndk_path}',
        f'-DCMAKE_SYSTEM_NAME=Android',
        f'-DCMAKE_SYSTEM_VERSION={abi_config["api_level"]}',
        f'-DCMAKE_BUILD_TYPE=Release',
        '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
        '-DBUILD_SHARED_LIBS=OFF',
    ] + abi_config['cmake_flags'] + [str(BORINGSSL_SRC)]

    print(f"⚙ Configuring CMake for {abi_name}...")
    print(f"  CMake args: {' '.join(cmake_args)}")
    subprocess.run(cmake_args, cwd=build_dir, check=True)
    
    # Verify CMake configuration
    if abi_name == 'arm64-v8a':
        # Check that CMake configured for correct architecture
        config_log = (build_dir / 'CMakeCache.txt')
        if config_log.exists():
            with open(config_log, 'r') as f:
                cache_content = f.read()
                if 'CMAKE_ANDROID_ARCH_ABI:STRING=arm64-v8a' not in cache_content:
                    print(f"⚠️  Warning: CMake cache may not have correct ABI setting")
                if 'aarch64' not in cache_content.lower():
                    print(f"⚠️  Warning: CMake may not be configured for aarch64")

    print(f"⚙ Building {abi_name}...")
    subprocess.run(['ninja'], cwd=build_dir, check=True)

    # Verify libraries were built
    libcrypto_path = build_dir / 'libcrypto.a'
    libssl_path = build_dir / 'libssl.a'
    
    if not libcrypto_path.exists():
        print(f"❌ ERROR: libcrypto.a not found in build directory")
        sys.exit(1)
    if not libssl_path.exists():
        print(f"❌ ERROR: libssl.a not found in build directory")
        sys.exit(1)

    # Copy libraries
    lib_output = LIB_DIR / abi_name
    if lib_output.exists():
        shutil.rmtree(lib_output)
    lib_output.mkdir(parents=True, exist_ok=True)

    shutil.copy2(libcrypto_path, lib_output / 'libcrypto.a')
    shutil.copy2(libssl_path, lib_output / 'libssl.a')

    # Verify copied libraries
    if not (lib_output / 'libcrypto.a').exists() or not (lib_output / 'libssl.a').exists():
        print(f"❌ ERROR: Failed to copy libraries")
        sys.exit(1)

    print(f"✓ {abi_name} build complete")
    print(f"  Libraries: {lib_output}/")
    print(f"  libcrypto.a: {(lib_output / 'libcrypto.a').stat().st_size / 1024 / 1024:.2f} MB")
    print(f"  libssl.a: {(lib_output / 'libssl.a').stat().st_size / 1024 / 1024:.2f} MB")

def copy_headers():
    """Copy BoringSSL headers to include directory"""
    print("\n⚙ Copying headers...")

    if INCLUDE_DIR.exists():
        shutil.rmtree(INCLUDE_DIR)

    src_include = BORINGSSL_SRC / 'include'
    shutil.copytree(src_include, INCLUDE_DIR)

    print(f"✓ Headers copied to {INCLUDE_DIR}")

def main():
    print("""
╔══════════════════════════════════════════════════════════════╗
║  BoringSSL Builder for Android - SimpleXray                  ║
║  Builds static libraries for all ABIs                        ║
╚══════════════════════════════════════════════════════════════╝
""")

    # Find NDK
    ndk_path = find_ndk()
    print(f"✓ Found Android NDK at: {ndk_path}")

    # Clone BoringSSL if needed
    clone_boringssl()

    # Build for all ABIs
    for abi_name, abi_config in ABIS.items():
        try:
            build_abi(abi_name, abi_config, ndk_path)
        except subprocess.CalledProcessError as e:
            print(f"✗ Failed to build {abi_name}: {e}")
            sys.exit(1)

    # Copy headers
    copy_headers()

    print(f"""
╔══════════════════════════════════════════════════════════════╗
║  ✓ ALL BUILDS COMPLETED SUCCESSFULLY                         ║
╚══════════════════════════════════════════════════════════════╝

Libraries built for: {', '.join(ABIS.keys())}
Output directory: {LIB_DIR}/
Headers directory: {INCLUDE_DIR}/

Next steps:
1. Verify libraries: ls -lh {LIB_DIR}/*/
2. Build SimpleXray: ./gradlew assembleDebug -PuseBoringssl=true
""")

if __name__ == '__main__':
    main()
