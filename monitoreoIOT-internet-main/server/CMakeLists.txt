cmake_minimum_required(VERSION 3.10)
project(server_iot LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(server
    src/main.cpp
    src/server.cpp
    src/logger.cpp
    src/protocol.cpp
)

target_include_directories(server PRIVATE include)

target_compile_options(server PRIVATE -Wall -Wextra -Wpedantic)

find_package(Threads REQUIRED)
target_link_libraries(server PRIVATE Threads::Threads)
