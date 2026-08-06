cmake_minimum_required(VERSION 3.22.1)

project(indexed_png LANGUAGES C CXX)

set(SPNG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/libspng)

add_library(indexed-png SHARED
    indexed_png_jni.cpp
    ${SPNG_DIR}/spng.c
)

target_include_directories(indexed-png PRIVATE ${SPNG_DIR})
target_compile_definitions(indexed-png PRIVATE SPNG_STATIC)
target_compile_options(indexed-png PRIVATE
    -O3
    -fstrict-aliasing
    -fvisibility=hidden
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(z-lib z)
find_library(m-lib m)
target_link_libraries(indexed-png
    ${log-lib}
    ${jnigraphics-lib}
    ${z-lib}
    ${m-lib}
)
