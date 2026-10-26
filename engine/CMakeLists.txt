cmake_minimum_required(VERSION 3.22)
project(sable_engine CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(SABLE_SANITIZE "Build with AddressSanitizer + UndefinedBehaviorSanitizer (host)" OFF)
option(SABLE_BUILD_TESTS "Build the engine test suite" ON)
option(SABLE_BUILD_CLI "Build the sable-cli analysis tool" ON)

# MATH §25.1: no FMA contraction, no fast-math, clang only.
if(NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
  message(FATAL_ERROR "Sable's engine is clang-only (MATH §25.1). Got ${CMAKE_CXX_COMPILER_ID}.")
endif()
set(SABLE_FP_FLAGS -ffp-contract=off -fno-fast-math -fno-finite-math-only)

add_library(sable_engine STATIC
  src/dmath.cpp
  src/analyzer.cpp
  src/dsp.cpp
  src/perceptual_fast.cpp)
target_include_directories(sable_engine PUBLIC include)
target_compile_options(sable_engine PRIVATE ${SABLE_FP_FLAGS} -Wall -Wextra -Wpedantic -Wconversion -Wshadow)

if(SABLE_SANITIZE)
  set(SAN -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=undefined)
  target_compile_options(sable_engine PUBLIC ${SAN})
  target_link_options(sable_engine PUBLIC ${SAN})
endif()

if(ANDROID)
  # Single static binary that can be run via `adb shell`.
  set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -static-libstdc++")
endif()

# Host-only full-resolution perceptual reference (MATH §10): validation, never shipped.
if(NOT ANDROID)
  add_library(sable_reference STATIC src/perceptual_ref.cpp)
  target_link_libraries(sable_reference PUBLIC sable_engine)
  target_include_directories(sable_reference PRIVATE src)
  target_compile_options(sable_reference PRIVATE ${SABLE_FP_FLAGS})
  add_executable(sable_perceptual tools/sable_perceptual.cpp)
  target_link_libraries(sable_perceptual PRIVATE sable_reference)
endif()

if(SABLE_BUILD_TESTS)
  add_executable(sable_tests
    tests/test_main.cpp
    tests/test_dmath.cpp
    tests/test_kweighting.cpp
    tests/test_compliance.cpp
    tests/test_properties.cpp
    tests/test_perceptual.cpp)
  target_include_directories(sable_tests PRIVATE third_party/doctest tests)
  target_link_libraries(sable_tests PRIVATE sable_engine)
  target_include_directories(sable_tests PRIVATE src)
  if(NOT ANDROID)
    target_link_libraries(sable_tests PRIVATE sable_reference)
    target_compile_definitions(sable_tests PRIVATE SABLE_HAVE_REFERENCE=1)
  endif()
  target_compile_options(sable_tests PRIVATE ${SABLE_FP_FLAGS})
endif()

if(SABLE_BUILD_CLI)
  add_executable(sable_cli tools/sable_cli.cpp)
  target_link_libraries(sable_cli PRIVATE sable_engine)
  target_compile_options(sable_cli PRIVATE ${SABLE_FP_FLAGS})
endif()
