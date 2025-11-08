# CMakeLists.txt for perf-net (Rust implementation)
# This module is now built using Cargo, not CMake
# Keep this file for reference but it won't be used

cmake_minimum_required(VERSION 3.22)

project(perf-net)

# NOTE: This module is now built using Rust/Cargo
# The Rust library will be built separately and linked
# See build-rust.sh or build.gradle buildRustModules task

# This file is kept for compatibility but the module is built via Rust

message(STATUS "⚠️  perf-net is now built using Rust/Cargo")
message(STATUS "⚠️  This CMakeLists.txt is kept for reference only")
