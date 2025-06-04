cmake_minimum_required(VERSION 3.6)

project(dngCreator)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

file(READ ${CMAKE_CURRENT_SOURCE_DIR}/../../../version.properties ver)
string(REGEX MATCH "VERSION_BUILD=([0-9]*)" _ ${ver})
set(VERSION_BUILD ${CMAKE_MATCH_1})
string(REGEX MATCH "VERSION_NAME=([0-9,.]*)" _ ${ver})
set(VERSION_NAME ${CMAKE_MATCH_1})

file(DOWNLOAD
        https://raw.githubusercontent.com/ParticlesDevs/tinydng/refs/heads/master/tiny_dng_writer.h
        ${CMAKE_CURRENT_SOURCE_DIR}/deps/tiny_dng_writer.h)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/deps)

set(SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR})
add_library(${CMAKE_PROJECT_NAME} SHARED
    ${SRC_DIR}/dngCreator.cpp
)
add_library(allocator SHARED
    ${SRC_DIR}/allocator.cpp
)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        VERSION_BUILD="${VERSION_BUILD}"
        VERSION_NAME="${VERSION_NAME}"
)

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
        log
)

target_link_libraries(allocator PRIVATE
        log
)

