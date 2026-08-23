cmake_minimum_required(VERSION 3.22.1)

project(indexed_heif LANGUAGES CXX)

add_library(indexed-heif SHARED indexed_heif_jni.cpp)

target_compile_options(indexed-heif PRIVATE
    -O3
    -fstrict-aliasing
    -fvisibility=hidden
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(z-lib z)
target_link_libraries(indexed-heif
    ${log-lib}
    ${jnigraphics-lib}
    ${z-lib}
)
