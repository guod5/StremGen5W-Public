cmake_minimum_required(VERSION 3.10.2)
project(gen5w_ffmpeg_audio C CXX)

set(CMAKE_CXX_STANDARD 11)
set(FFMPEG_SOURCE "${CMAKE_CURRENT_LIST_DIR}/ffmpeg")
set(FFMPEG_LIB_DIR "${FFMPEG_SOURCE}/android-libs/${ANDROID_ABI}")

foreach(name avutil swresample avcodec)
    add_library(${name} STATIC IMPORTED)
    set_target_properties(${name} PROPERTIES
        IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/lib${name}.a")
endforeach()

include_directories("${FFMPEG_SOURCE}")
find_library(ANDROID_LOG_LIBRARY log)
find_library(ANDROID_LIB android)
add_library(ffmpegJNI SHARED ffmpeg_jni.cc)
target_link_libraries(ffmpegJNI PRIVATE
    ${ANDROID_LIB} swresample avcodec avutil ${ANDROID_LOG_LIBRARY} m)
