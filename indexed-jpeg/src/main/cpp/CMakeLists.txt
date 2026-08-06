cmake_minimum_required(VERSION 3.22.1)

project(indexed_jpeg LANGUAGES C CXX)

set(JPEG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/libjpeg-turbo-indexed)

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

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(indexed-jpeg ${log-lib} ${jnigraphics-lib})
