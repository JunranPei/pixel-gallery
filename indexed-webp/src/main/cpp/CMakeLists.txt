cmake_minimum_required(VERSION 3.22.1)

project(indexed_webp LANGUAGES CXX)

add_library(indexed-webp SHARED indexed_webp_jni.cpp)

target_compile_options(indexed-webp PRIVATE
    -O3
    -fstrict-aliasing
    -fvisibility=hidden
)

find_library(dl-lib dl)
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(z-lib z)
target_link_libraries(indexed-webp
    ${dl-lib}
    ${log-lib}
    ${jnigraphics-lib}
    ${z-lib}
)
