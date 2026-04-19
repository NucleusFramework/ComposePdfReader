cmake_minimum_required(VERSION 3.22.1)
project(pdfiumjni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Headers staged by the Gradle task `installPdfiumHeaders`.
set(PDFIUM_INCLUDE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../build/pdfium/include")
if(NOT EXISTS "${PDFIUM_INCLUDE_DIR}")
    message(FATAL_ERROR "PDFium headers missing: ${PDFIUM_INCLUDE_DIR}. Run `gradle installPdfiumHeaders` first.")
endif()

# Add libpdfium.so as an imported target — pulled from jniLibs by AGP at runtime.
add_library(pdfium SHARED IMPORTED)
set_target_properties(pdfium PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}/libpdfium.so"
)

add_library(pdfiumjni SHARED
    ../../jvmMain/native/pdfium_jni.cpp
    pdfium_android.cpp)
target_include_directories(pdfiumjni PRIVATE "${PDFIUM_INCLUDE_DIR}")
target_link_libraries(pdfiumjni PRIVATE pdfium log android jnigraphics)
