cmake_minimum_required(VERSION 3.22.1)

project(indexed_jpeg LANGUAGES C CXX)

set(JPEG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/libjpeg-turbo-indexed)
set(TURBO_ARCHIVE ${CMAKE_CURRENT_SOURCE_DIR}/vendor/libjpeg-turbo-3.2.0.tar.gz)
set(TURBO_EXTRACT_ROOT ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo-source)
set(TURBO_SOURCE_DIR ${TURBO_EXTRACT_ROOT}/libjpeg-turbo-3.2.0)

if(NOT EXISTS ${TURBO_SOURCE_DIR}/CMakeLists.txt)
    file(MAKE_DIRECTORY ${TURBO_EXTRACT_ROOT})
    file(ARCHIVE_EXTRACT INPUT ${TURBO_ARCHIVE} DESTINATION ${TURBO_EXTRACT_ROOT})
endif()

# The Android indexed decoder below is an old, deliberately patched libjpeg
# fork. Keep it intact for sample=1 seek decoding, and build an isolated modern
# libjpeg-turbo for the independent standard JPEG blocks in a v7 pyramid.
# libjpeg-turbo deliberately requires a separate CMake build rather than
# add_subdirectory(), so make that build an explicit native dependency.
include(ExternalProject)
set(TURBO_BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo-build)
set(TURBO_LIBRARY ${TURBO_BINARY_DIR}/libjpeg.a)
ExternalProject_Add(libjpeg-turbo-simd-build
    SOURCE_DIR ${TURBO_SOURCE_DIR}
    BINARY_DIR ${TURBO_BINARY_DIR}
    CMAKE_GENERATOR Ninja
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_NDK=${ANDROID_NDK}
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_TURBOJPEG=OFF
        -DWITH_TOOLS=OFF
        -DWITH_TESTS=OFF
        -DWITH_FUZZ=OFF
        -DWITH_SIMD=ON
        -DREQUIRE_SIMD=ON
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target jpeg-static
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS ${TURBO_LIBRARY}
)

add_library(libjpeg-turbo-simd STATIC IMPORTED)
set_target_properties(libjpeg-turbo-simd PROPERTIES
    IMPORTED_LOCATION ${TURBO_LIBRARY}
)
add_dependencies(libjpeg-turbo-simd libjpeg-turbo-simd-build)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

add_library(indexed-jpeg-simd SHARED
    indexed_jpeg_simd.cpp
)
target_include_directories(indexed-jpeg-simd PRIVATE
    ${TURBO_SOURCE_DIR}/src
    ${TURBO_BINARY_DIR}
)
target_compile_options(indexed-jpeg-simd PRIVATE
    -O3
    -fstrict-aliasing
    -fvisibility=hidden
)
set_target_properties(indexed-jpeg-simd PROPERTIES
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
add_dependencies(indexed-jpeg-simd libjpeg-turbo-simd-build)
target_link_libraries(indexed-jpeg-simd PRIVATE libjpeg-turbo-simd ${log-lib})
target_link_options(indexed-jpeg-simd PRIVATE
    "-Wl,--exclude-libs,ALL"
)

add_library(indexed-jpeg SHARED
    indexed_jpeg_jni.cpp
    ${JPEG_DIR}/jaricom.c
    ${JPEG_DIR}/jcapimin.c
    ${JPEG_DIR}/jcapistd.c
    ${JPEG_DIR}/jccoefct.c
    ${JPEG_DIR}/jccolor.c
    ${JPEG_DIR}/jcdctmgr.c
    ${JPEG_DIR}/jchuff.c
    ${JPEG_DIR}/jcinit.c
    ${JPEG_DIR}/jcmainct.c
    ${JPEG_DIR}/jcmarker.c
    ${JPEG_DIR}/jcmaster.c
    ${JPEG_DIR}/jcomapi.c
    ${JPEG_DIR}/jcparam.c
    ${JPEG_DIR}/jcphuff.c
    ${JPEG_DIR}/jcprepct.c
    ${JPEG_DIR}/jcsample.c
    ${JPEG_DIR}/jctrans.c
    ${JPEG_DIR}/jdapimin.c
    ${JPEG_DIR}/jdapistd.c
    ${JPEG_DIR}/jdatadst.c
    ${JPEG_DIR}/jdatasrc.c
    ${JPEG_DIR}/jdcoefct.c
    ${JPEG_DIR}/jdcolor.c
    ${JPEG_DIR}/jddctmgr.c
    ${JPEG_DIR}/jdhuff.c
    ${JPEG_DIR}/jdinput.c
    ${JPEG_DIR}/jdmainct.c
    ${JPEG_DIR}/jdmarker.c
    ${JPEG_DIR}/jdmaster.c
    ${JPEG_DIR}/jdmerge.c
    ${JPEG_DIR}/jdphuff.c
    ${JPEG_DIR}/jdpostct.c
    ${JPEG_DIR}/jdsample.c
    ${JPEG_DIR}/jdtrans.c
    ${JPEG_DIR}/jerror.c
    ${JPEG_DIR}/jfdctflt.c
    ${JPEG_DIR}/jfdctfst.c
    ${JPEG_DIR}/jfdctint.c
    ${JPEG_DIR}/jidctflt.c
    ${JPEG_DIR}/jidctfst.c
    ${JPEG_DIR}/jidctint.c
    ${JPEG_DIR}/jidctred.c
    ${JPEG_DIR}/jquant1.c
    ${JPEG_DIR}/jquant2.c
    ${JPEG_DIR}/jutils.c
    ${JPEG_DIR}/jmemmgr.c
    ${JPEG_DIR}/jmemnobs.c
    ${JPEG_DIR}/jcarith.c
    ${JPEG_DIR}/jdarith.c
    ${JPEG_DIR}/jsimd_none.c
)

target_include_directories(indexed-jpeg PRIVATE ${JPEG_DIR})
target_compile_definitions(indexed-jpeg PRIVATE
    ANDROID
    ANDROID_TILE_BASED_DECODE
)
target_compile_options(indexed-jpeg PRIVATE
    -O3
    -fstrict-aliasing
    -fvisibility=hidden
)

target_link_libraries(indexed-jpeg
    indexed-jpeg-simd
    ${log-lib}
    ${jnigraphics-lib}
)
