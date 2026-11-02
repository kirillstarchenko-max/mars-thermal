cmake_minimum_required(VERSION 3.22.1)
project("thermal")

add_library(thermal SHARED thermal_renderer.cpp)

target_link_libraries(thermal
    android
    jnigraphics
    log)
