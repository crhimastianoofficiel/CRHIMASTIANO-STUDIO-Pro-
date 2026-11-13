cmake_minimum_required(VERSION 3.22.1)
project(studio_audio)

find_package(oboe REQUIRED CONFIG)

add_library(studio_audio SHARED
    native_audio.cpp
)

target_compile_features(studio_audio PRIVATE cxx_std_17)
target_link_libraries(studio_audio
    oboe::oboe
    log
)
