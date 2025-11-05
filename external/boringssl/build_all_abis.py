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
        print(f"[OK] BoringSSL source already exists at {BORINGSSL_SRC}")
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

    print("[*] Cloning BoringSSL repository...")
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
        print(f"[OK] BoringSSL cloned successfully (commit: {commit_hash})")
    except subprocess.CalledProcessError:
        print("[OK] BoringSSL cloned successfully")

def filter_archive_by_arch(archive_path, target_arch):
    """Filter a static library archive to only include object files compatible with target_arch"""
    import tempfile
    
    # Map ABI names to architecture patterns expected in file/readelf output
    arch_patterns = {
        'arm64-v8a': ['aarch64', 'arm64', 'ARM aarch64'],
        'armeabi-v7a': ['arm', 'ARM, EABI5'],
        'x86_64': ['x86-64', 'x86_64', 'Intel 80386'],
        'x86': ['Intel 80386', 'i386', 'i686'],
    }
    
    if target_arch not in arch_patterns:
        print(f"[WARN]  Warning: Unknown ABI {target_arch}, skipping archive filtering")
        return False
    
    patterns = arch_patterns[target_arch]
    temp_dir = Path(tempfile.mkdtemp())
    
    try:
        # List all members in the archive
        result = subprocess.run(
            ['ar', 't', str(archive_path)],
            capture_output=True,
            text=True,
            check=True
        )
        members = [line.strip() for line in result.stdout.splitlines() if line.strip()]
        
        if not members:
            print(f"[WARN]  Warning: Archive {archive_path.name} is empty")
            return False
        
        # Extract each member individually to check architecture
        compatible_members = []
        incompatible_count = 0
        
        for member in members:
            # Extract this specific member
            member_extract_dir = temp_dir / f'member_{len(compatible_members) + incompatible_count}'
            member_extract_dir.mkdir()
            
            try:
                subprocess.run(
                    ['ar', 'x', str(archive_path), member],
                    cwd=member_extract_dir,
                    check=True,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL
                )
                
                # Find extracted file
                extracted_files = list(member_extract_dir.glob('*'))
                if not extracted_files:
                    # Member might be a directory or special file, assume compatible
                    compatible_members.append(member)
                    shutil.rmtree(member_extract_dir, ignore_errors=True)
                    continue
                
                extracted_file = extracted_files[0]
                is_compatible = False
                
                # Check architecture using 'file' command
                try:
                    file_result = subprocess.run(
                        ['file', str(extracted_file)],
                        capture_output=True,
                        text=True,
                        check=True
                    )
                    file_output = file_result.stdout.lower()
                    
                    # Check if this file matches target architecture
                    is_compatible = any(pattern.lower() in file_output for pattern in patterns)
                    
                    # For ARM64, also check that it's NOT x86_64
                    if target_arch == 'arm64-v8a':
                        is_compatible = is_compatible and 'x86-64' not in file_output and 'x86_64' not in file_output and 'intel 80386' not in file_output
                    # For x86_64, check that it's NOT ARM
                    elif target_arch == 'x86_64':
                        is_compatible = is_compatible and 'arm' not in file_output and 'aarch64' not in file_output
                    
                except (subprocess.CalledProcessError, FileNotFoundError):
                    # If 'file' command fails, try readelf as fallback
                    try:
                        readelf_result = subprocess.run(
                            ['readelf', '-h', str(extracted_file)],
                            capture_output=True,
                            text=True
                        )
                        if readelf_result.returncode == 0:
                            readelf_output = readelf_result.stdout.lower()
                            is_compatible = any(pattern.lower() in readelf_output for pattern in patterns)
                            if target_arch == 'arm64-v8a':
                                is_compatible = is_compatible and 'x86-64' not in readelf_output and 'x86_64' not in readelf_output
                            elif target_arch == 'x86_64':
                                is_compatible = is_compatible and 'arm' not in readelf_output and 'aarch64' not in readelf_output
                        else:
                            # If both fail, assume compatible (better safe than sorry)
                            is_compatible = True
                    except (subprocess.CalledProcessError, FileNotFoundError):
                        # If both commands fail, assume compatible
                        is_compatible = True
                
                if is_compatible:
                    compatible_members.append(member)
                else:
                    incompatible_count += 1
                
            except subprocess.CalledProcessError:
                # If extraction fails, assume compatible to be safe
                compatible_members.append(member)
            finally:
                shutil.rmtree(member_extract_dir, ignore_errors=True)
        
        if incompatible_count > 0:
            print(f"   Filtered out {incompatible_count} incompatible object files")
        
        # Rebuild archive with only compatible members
        if compatible_members and len(compatible_members) > 0:
            backup_path = archive_path.with_suffix('.a.bak')
            shutil.copy2(archive_path, backup_path)
            
            # Extract all compatible members to a clean directory
            rebuild_dir = temp_dir / 'rebuild'
            rebuild_dir.mkdir()
            
            # Extract compatible members
            for member in compatible_members:
                try:
                    subprocess.run(
                        ['ar', 'x', str(backup_path), member],
                        cwd=rebuild_dir,
                        check=True,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL
                    )
                except subprocess.CalledProcessError:
                    # If extraction fails, skip this member
                    continue
            
            # Create new archive with all extracted files
            archive_path.unlink()
            extracted_objs = list(rebuild_dir.glob('*'))
            if extracted_objs:
                subprocess.run(
                    ['ar', 'rcs', str(archive_path)] + [str(f.name) for f in extracted_objs],
                    cwd=rebuild_dir,
                    check=True
                )
            
            # Verify new archive
            if archive_path.exists() and archive_path.stat().st_size > 0:
                backup_path.unlink()
                return True
            else:
                # Restore backup if rebuild failed
                shutil.move(backup_path, archive_path)
                print(f"[WARN]  Warning: Failed to rebuild archive, using original")
                return False
        
        return False
        
    except Exception as e:
        print(f"[WARN]  Warning: Error filtering archive {archive_path.name}: {e}")
        return False
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)

def build_abi(abi_name, abi_config, ndk_path):
    """Build BoringSSL for a specific ABI"""
    print(f"\n{'='*60}")
    print(f"Building BoringSSL for {abi_name}")
    print(f"{'='*60}")

    build_dir = BUILD_DIR / abi_name
    # Clean build directory to prevent cross-contamination
    if build_dir.exists():
        print(f" Cleaning previous build for {abi_name}...")
        shutil.rmtree(build_dir)
    build_dir.mkdir(parents=True, exist_ok=True)

    toolchain_file = ndk_path / 'build/cmake/android.toolchain.cmake'
    if not toolchain_file.exists():
        print(f"ERROR: NDK toolchain file not found at {toolchain_file}")
        sys.exit(1)

    # Check if ninja is available
    ninja_available = False
    ninja_cmd = None
    cmake_make_program = None
    
    # First, try to find ninja.exe directly (for pip-installed ninja)
    try:
        import site
        home = Path(os.path.expanduser('~'))
        possible_paths = [
            Path(site.USER_BASE) / 'Scripts' / 'ninja.exe',
            home / 'AppData' / 'Roaming' / 'Python' / 'Scripts' / 'ninja.exe',
        ]
        # Check versioned Python directories
        python_base = home / 'AppData' / 'Roaming' / 'Python'
        if python_base.exists():
            for py_dir in python_base.glob('Python*/Scripts'):
                possible_paths.append(py_dir / 'ninja.exe')
        
        for ninja_exe in possible_paths:
            if ninja_exe.exists():
                ninja_available = True
                ninja_cmd = ['python', '-m', 'ninja']  # Mark as pip-installed
                cmake_make_program = str(ninja_exe.resolve())
                print(f"[*] Found ninja.exe at: {cmake_make_program}")
                break
    except Exception:
        pass
    
    # If not found, try ninja in PATH
    if not ninja_available:
        try:
            subprocess.run(['ninja', '--version'], capture_output=True, check=True)
            ninja_available = True
            ninja_cmd = 'ninja'
        except (subprocess.CalledProcessError, FileNotFoundError):
            ninja_available = False

    # Choose build generator
    if ninja_available:
        generator = 'Ninja'
        print("[OK] Using Ninja build system")
    else:
        generator = 'Unix Makefiles'
        print("[WARN]  Ninja not found, using Unix Makefiles instead")
    
    # Use cmake --build which works with any generator
    # --parallel speeds up builds, --config is for multi-config generators
    num_cores = os.cpu_count() or 4
    build_cmd = ['cmake', '--build', '.', '--parallel', str(num_cores)]
    
    # cmake_make_program is already set if we found ninja.exe above
    # For ninja in PATH, CMake should find it automatically
    if ninja_available and not cmake_make_program and ninja_cmd == 'ninja':
        # Try to locate ninja explicitly (optional)
        try:
            result = subprocess.run(['where', 'ninja'], capture_output=True, text=True, check=True)
            if result.stdout.strip():
                cmake_make_program = result.stdout.strip().split('\n')[0]
        except (subprocess.CalledProcessError, FileNotFoundError):
            pass

    cmake_args = [
        'cmake',
        f'-G{generator}',
        f'-DCMAKE_TOOLCHAIN_FILE={toolchain_file}',
        f'-DCMAKE_ANDROID_NDK={ndk_path}',
        f'-DCMAKE_SYSTEM_NAME=Android',
        f'-DCMAKE_SYSTEM_VERSION={abi_config["api_level"]}',
        f'-DCMAKE_BUILD_TYPE=Release',
        '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
        '-DBUILD_SHARED_LIBS=OFF',
    ] + abi_config['cmake_flags']
    
    # Add CMAKE_MAKE_PROGRAM if we found ninja.exe
    if cmake_make_program:
        cmake_args.append(f'-DCMAKE_MAKE_PROGRAM={cmake_make_program}')
    
    cmake_args.append(str(BORINGSSL_SRC))

    print(f"[*] Configuring CMake for {abi_name}...")
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
                    print(f"[WARN]  Warning: CMake cache may not have correct ABI setting")
                if 'aarch64' not in cache_content.lower():
                    print(f"[WARN]  Warning: CMake may not be configured for aarch64")

    print(f"[*] Building {abi_name}...")
    subprocess.run(build_cmd, cwd=build_dir, check=True)

    # Verify libraries were built
    libcrypto_path = build_dir / 'libcrypto.a'
    libssl_path = build_dir / 'libssl.a'
    
    if not libcrypto_path.exists():
        print(f"[ERROR] ERROR: libcrypto.a not found in build directory")
        sys.exit(1)
    if not libssl_path.exists():
        print(f"[ERROR] ERROR: libssl.a not found in build directory")
        sys.exit(1)

    # Filter archives to remove incompatible object files
    print(f"[check] Filtering archives for {abi_name}...")
    filter_archive_by_arch(libcrypto_path, abi_name)
    filter_archive_by_arch(libssl_path, abi_name)

    # Copy libraries
    lib_output = LIB_DIR / abi_name
    if lib_output.exists():
        shutil.rmtree(lib_output)
    lib_output.mkdir(parents=True, exist_ok=True)

    shutil.copy2(libcrypto_path, lib_output / 'libcrypto.a')
    shutil.copy2(libssl_path, lib_output / 'libssl.a')

    # Verify copied libraries
    if not (lib_output / 'libcrypto.a').exists() or not (lib_output / 'libssl.a').exists():
        print(f"[ERROR] ERROR: Failed to copy libraries")
        sys.exit(1)

    print(f"[OK] {abi_name} build complete")
    print(f"  Libraries: {lib_output}/")
    print(f"  libcrypto.a: {(lib_output / 'libcrypto.a').stat().st_size / 1024 / 1024:.2f} MB")
    print(f"  libssl.a: {(lib_output / 'libssl.a').stat().st_size / 1024 / 1024:.2f} MB")

def copy_headers():
    """Copy BoringSSL headers to include directory"""
    print("\n[*] Copying headers...")

    if INCLUDE_DIR.exists():
        shutil.rmtree(INCLUDE_DIR)

    src_include = BORINGSSL_SRC / 'include'
    shutil.copytree(src_include, INCLUDE_DIR)

    print(f"[OK] Headers copied to {INCLUDE_DIR}")

def main():
    print("""
==============================================================
  BoringSSL Builder for Android - SimpleXray
  Builds static libraries for all ABIs
==============================================================
""")

    # Find NDK
    ndk_path = find_ndk()
    print(f"[OK] Found Android NDK at: {ndk_path}")

    # Clone BoringSSL if needed
    clone_boringssl()

    # Build for all ABIs
    for abi_name, abi_config in ABIS.items():
        try:
            build_abi(abi_name, abi_config, ndk_path)
        except subprocess.CalledProcessError as e:
            print(f"[FAILED] Failed to build {abi_name}: {e}")
            sys.exit(1)

    # Copy headers
    copy_headers()

    print(f"""
==============================================================
  ALL BUILDS COMPLETED SUCCESSFULLY
==============================================================

Libraries built for: {', '.join(ABIS.keys())}
Output directory: {LIB_DIR}/
Headers directory: {INCLUDE_DIR}/

Next steps:
1. Verify libraries: ls -lh {LIB_DIR}/*/
2. Build SimpleXray: ./gradlew assembleDebug -PuseBoringssl=true
""")

if __name__ == '__main__':
    main()
