cmake_minimum_required(VERSION 3.22.1)
project("softetherclient")

# Build the native test library (libsoftether_test.so).
#
# DEEP-REVIEW-VERIFY L-01/L-02: default OFF, so the test library is NOT part of
# a plain release build. It is enabled explicitly by the debug and androidTest
# Gradle configurations via `-DBUILD_TESTING=ON`. See the guarded target near
# the bottom of this file for the full rationale.
option(BUILD_TESTING "Build the native test library" OFF)

set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Find required libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(z-lib z)

# Directories
set(PREBUILT_JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs)

# Prebuilt OpenSSL libraries
set(OPENSSL_ROOT ${PREBUILT_JNILIBS_DIR}/${ANDROID_ABI})
set(OPENSSL_LIBRARIES
    ${OPENSSL_ROOT}/libssl.a
    ${OPENSSL_ROOT}/libcrypto.a
)

# Check if prebuilt libraries exist
if(EXISTS ${OPENSSL_ROOT}/libssl.a AND EXISTS ${OPENSSL_ROOT}/libcrypto.a)
    message(STATUS "Using prebuilt OpenSSL from ${OPENSSL_ROOT}")
    set(USE_PREBUILT_OPENSSL TRUE)
else()
    message(WARNING "Prebuilt OpenSSL not found in ${OPENSSL_ROOT}, trying system OpenSSL")
    set(USE_PREBUILT_OPENSSL FALSE)
endif()

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/softether-core/include
    ${CMAKE_CURRENT_SOURCE_DIR}/openssl/include
)

# If prebuilt OpenSSL exists, also include its headers from build dir
if(USE_PREBUILT_OPENSSL)
    # OpenSSL headers are typically in the build directory after install
    # For now, we'll use the system headers or create a minimal include dir
    file(MAKE_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/openssl/include/openssl)
endif()

# Source files for main library
set(SOFTETHER_SOURCES
    jni/softether_jni.c
    softether-core/src/proto/softether_protocol.c
    softether-core/src/proto/softether_pack.c
    softether-core/src/proto/softether_nat_t.c
    softether-core/src/proto/rudp_transport.c
    softether-core/src/proto/softether_rudp.c
    softether-core/src/crypto/aes_wrapper.c
    softether-core/src/crypto/compress.c
    softether-core/src/socket/tcp_socket.c
    softether-core/src/packet/packet_handler.c
    softether-core/src/net/dhcp_client.c
)

# Create main shared library
add_library(softether SHARED ${SOFTETHER_SOURCES})

# Link libraries for main library
target_link_libraries(softether
    ${log-lib}
    ${android-lib}
    ${z-lib}
)

# Link prebuilt OpenSSL if available
if(USE_PREBUILT_OPENSSL)
    target_link_libraries(softether ${OPENSSL_LIBRARIES})
else()
    # Fallback to system OpenSSL
    target_link_libraries(softether ssl crypto)
endif()

# Compiler flags
target_compile_options(softether PRIVATE
    -Wall
    -Wno-unused-parameter
    -Wno-unused-variable
    -fPIC
)

# Phase 13A: per-packet data-path trace logging. Off by default — a formatted
# logd write per packet dominates mobile throughput. Enable for data-path
# debugging (e.g. -DSE_TRACE_PACKETS=ON via externalNativeBuild arguments).
option(SE_TRACE_PACKETS "Enable per-packet trace logging" OFF)
if(SE_TRACE_PACKETS)
    add_compile_definitions(SE_TRACE_PACKETS=1)
endif()

# Debug/Release specific flags
#
# DEEP-REVIEW-VERIFY L-02/L-03: the release artifact must not ship debug
# symbols or build-machine source paths. Previously the release branch only
# added -O2, so the shipped libsoftether.so still contained .symtab, .strtab,
# full .debug_* sections (loc/abbrev/info/ranges/str/line) and embedded paths
# such as `/src/main/cpp/softether-core/src/proto/softether_protocol.c` — a
# symbol/source-path disclosure plus a size cost. -Wl,-s asks the linker to
# drop static symbols and debug info.
#
# This is safe for a JNI library: the runtime linker resolves JNI entry points
# through .dynsym, which -Wl,-s preserves. Only .symtab / .strtab / .debug_*
# are removed.
#
# The debug branch is deliberately left untouched so the debug APK can still
# symbolicate (that is why this is scoped to the else() below and not applied
# globally).
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(softether PRIVATE -g -O0)
else()
    target_compile_options(softether PRIVATE -O2)
    # Strip static symbols + debug info from the release shared library.
    target_link_options(softether PRIVATE -Wl,-s)
endif()

# Test library sources
#
# Kept defined unconditionally so the list remains available for reference and
# for any future consumer; it is only *used* inside the BUILD_TESTING guard
# below, which is what keeps the test target out of the release artifact.
set(TEST_SOURCES
    test/native_test.c
    test/test_jni_bridge.c
    ${SOFTETHER_SOURCES}
)

# Test-only shared library: native_test + test_jni_bridge plus the production
# sources they exercise.
#
# DEEP-REVIEW-VERIFY L-01/L-02: this target was previously built
# unconditionally, so libsoftether_test.so (≈5.7 MB per ABI, ~21 MB across the
# four ABIs) was packaged into EVERY variant — including the release APK — and
# exposed test-only JNI entry points (native_test, test_jni_bridge, JNI_OnLoad)
# to end users. R8/`shrinkResources` cannot remove a native library, so the
# packaging fix has to be made here, at the CMake layer.
#
# The default is OFF, so a plain release build never produces this target. It is
# enabled explicitly by the debug and androidTest Gradle configurations via
# `-DBUILD_TESTING=ON` (see SoftEtherClient/build.gradle). This keeps Android
# instrumentation tests (which `System.loadLibrary("softether_test")`) working
# while removing the library from release output.
if(BUILD_TESTING)
    # Create test shared library
    add_library(softether_test SHARED ${TEST_SOURCES})

    # Include directories for test library
    target_include_directories(softether_test PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/softether-core/include
        ${CMAKE_CURRENT_SOURCE_DIR}/test
    )

    # Link libraries for test library
    target_link_libraries(softether_test
        ${log-lib}
        ${android-lib}
        ${z-lib}
    )

    # Link prebuilt OpenSSL for test library if available
    if(USE_PREBUILT_OPENSSL)
        target_link_libraries(softether_test ${OPENSSL_LIBRARIES})
    else()
        target_link_libraries(softether_test ssl crypto)
    endif()

    # Compiler flags for test library
    target_compile_options(softether_test PRIVATE
        -Wall
        -Wno-unused-parameter
        -Wno-unused-variable
        -fPIC
    )
endif()

# Export compile commands for IDE support
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
