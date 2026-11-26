cmake_minimum_required(VERSION 3.22)
project(inkflow_mobi C)

set(MOBI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/libmobi)
add_library(mobi SHARED
    ${MOBI_DIR}/buffer.c
    ${MOBI_DIR}/compression.c
    ${MOBI_DIR}/debug.c
    ${MOBI_DIR}/encryption.c
    ${MOBI_DIR}/index.c
    ${MOBI_DIR}/memory.c
    ${MOBI_DIR}/meta.c
    ${MOBI_DIR}/parse_rawml.c
    ${MOBI_DIR}/randombytes.c
    ${MOBI_DIR}/read.c
    ${MOBI_DIR}/sha1.c
    ${MOBI_DIR}/structure.c
    ${MOBI_DIR}/util.c
    ${MOBI_DIR}/write.c
)
target_compile_features(mobi PRIVATE c_std_99)
target_compile_definitions(mobi PRIVATE USE_ENCRYPTION HAVE_STRDUP HAVE_UNISTD_H MOBI_INLINE=inline PACKAGE_VERSION="0.12")
target_include_directories(mobi PUBLIC ${MOBI_DIR})
target_link_libraries(mobi z)

add_library(inkflow_mobi SHARED mobi_bridge.c)
target_link_libraries(inkflow_mobi mobi)
