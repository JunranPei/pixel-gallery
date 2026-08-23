cmake_minimum_required(VERSION 3.22.1)

project(indexed_raw LANGUAGES CXX)

file(GLOB_RECURSE LIBRAW_SOURCES CONFIGURE_DEPENDS vendor/LibRaw/src/*.cpp)
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "_ph\\.cpp$")

add_library(indexed-raw SHARED indexed_raw_jni.cpp ${LIBRAW_SOURCES})

target_include_directories(indexed-raw PRIVATE vendor/LibRaw)
target_compile_definitions(indexed-raw PRIVATE
    LIBRAW_NOTHREADS
    LIBRAW_CALLOC_RAWSTORE
    USE_ZLIB
)
target_compile_options(indexed-raw PRIVATE
    -O3
    -fvisibility=hidden
    -Wno-deprecated-declarations
    -Wno-unused-parameter
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(z-lib z)
find_library(m-lib m)
target_link_libraries(indexed-raw
    ${log-lib}
    ${jnigraphics-lib}
    ${z-lib}
    ${m-lib}
)
