cmake_minimum_required(VERSION 3.22)
project(loghook LANGUAGES CXX)

find_package(shadowhook REQUIRED CONFIG)

add_library(loghook SHARED loghook.cpp)
target_compile_features(loghook PRIVATE cxx_std_17)
target_link_libraries(loghook PRIVATE shadowhook::shadowhook log)
target_link_options(loghook PRIVATE -Wl,-z,max-page-size=16384)
