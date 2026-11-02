cmake_minimum_required(VERSION 3.22.1)
project("omr_cv_neon" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math -flto")

add_library(omr_cv_neon SHARED omr_cv_neon.cpp)
find_library(log-lib log)
target_link_libraries(omr_cv_neon ${log-lib})
