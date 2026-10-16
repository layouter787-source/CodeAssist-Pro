cmake_minimum_required(VERSION 3.22.1)
project(codeassist_ndk_sample LANGUAGES CXX)

add_library(native-lib SHARED native-lib.cpp)

find_library(android-log-lib log)
target_link_libraries(native-lib PRIVATE ${android-log-lib})
target_compile_features(native-lib PRIVATE cxx_std_17)
target_compile_options(native-lib PRIVATE -Wall -Wextra -Werror)
