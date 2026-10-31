cmake_minimum_required(VERSION 3.22.1)
project(rlinhook)

find_package(shadowhook REQUIRED CONFIG)

add_library(rlinhook SHARED rlinhook.cpp)
target_compile_options(rlinhook PRIVATE -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)
target_link_libraries(rlinhook shadowhook::shadowhook EGL GLESv3 log)
