cmake_minimum_required(VERSION 3.22.1)
project(topshe_llama C CXX)

set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
add_compile_options(-O3)

include(FetchContent)

# Only the core library: no tools, servers, tests or network code
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
# Realme 16 (Dimensity 6400: Cortex-A76/A55) supports dot-product + fp16
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

# Pinned llama.cpp release (Nov 2026, supports Qwen 3.5). The trainer notebook uses the SAME tag
# to make the GGUF, so phone and training always agree.
FetchContent_Declare(
    llama
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b12602.tar.gz
)
FetchContent_MakeAvailable(llama)

add_library(topshe_llama SHARED topshe_llama.cpp)
target_link_libraries(topshe_llama llama ggml android log)
