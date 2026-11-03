cmake_minimum_required(VERSION 3.22.1)

project(indexed_bmp LANGUAGES CXX)

add_library(indexed-bmp SHARED indexed_bmp_jni.cpp)

target_compile_options(indexed-bmp PRIVATE
    -O3
    -fstrict-aliasing
    -fvisibility=hidden
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(indexed-bmp
    ${log-lib}
    ${jnigraphics-lib}
)
