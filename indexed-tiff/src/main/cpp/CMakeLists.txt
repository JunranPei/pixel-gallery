cmake_minimum_required(VERSION 3.22.1)

project(indexed_tiff LANGUAGES CXX)

add_library(indexed-tiff SHARED indexed_tiff_jni.cpp)

target_compile_options(indexed-tiff PRIVATE
    -O3
    -fstrict-aliasing
    -fvisibility=hidden
)

find_library(dl-lib dl)
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(indexed-tiff
    ${dl-lib}
    ${log-lib}
    ${jnigraphics-lib}
)
