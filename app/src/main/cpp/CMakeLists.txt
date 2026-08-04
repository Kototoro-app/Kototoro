cmake_minimum_required(VERSION 3.22.1)
project("realesrgan_ncnn")

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fopenmp")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -fopenmp")

set(NCNN_DIR ${CMAKE_SOURCE_DIR}/ncnn/ncnn-20240410-android-vulkan/${ANDROID_ABI})
include_directories(${NCNN_DIR}/include/ncnn)
include_directories(${CMAKE_SOURCE_DIR})

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(vulkan-lib vulkan)
find_library(android-lib android)

add_library(realesrgan_ncnn SHARED
    realesrgan.cpp
    realesrgan_jni.cpp
    realcugan.cpp
    realcugan_jni.cpp
)

target_link_directories(realesrgan_ncnn PRIVATE ${NCNN_DIR}/lib)

target_link_libraries(realesrgan_ncnn
    ncnn
    glslang
    SPIRV
    MachineIndependent
    OGLCompiler
    OSDependent
    GenericCodeGen
    glslang-default-resource-limits
    ${log-lib}
    ${jnigraphics-lib}
    ${vulkan-lib}
    ${android-lib}
)
