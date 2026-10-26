cmake_minimum_required(VERSION 3.22)
project(sable_jni CXX)
set(SABLE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SABLE_BUILD_CLI OFF CACHE BOOL "" FORCE)
# The same portable engine that the host tests verify (MATH §25).
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../engine ${CMAKE_BINARY_DIR}/engine)
add_library(sable_jni SHARED sable_jni.cpp)
target_compile_options(sable_jni PRIVATE -ffp-contract=off -fno-fast-math)
target_link_libraries(sable_jni PRIVATE sable_engine log)
