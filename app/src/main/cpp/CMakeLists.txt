cmake_minimum_required(VERSION 3.22.1)
project(codeedit_native LANGUAGES C CXX)

# Targets:
#   codeedit_native   shared JNI library loaded by app.codeedit.highlight.TmNative (Android + optional host)
#   tmcore            static TextMate engine (port of vscode-textmate) used by the library and the host tests
#   onig              vendored Oniguruma 6.9.10 (UTF-16LE + ASCII only), no network access needed
# Host (non-Android) switches: -DCODEEDIT_HOST_TESTS=ON builds native-tests/* runners, -DCODEEDIT_HOST_JNI=ON the JNI .so.
option(CODEEDIT_HOST_TESTS "Build the host test runners (native-tests/)" OFF)
option(CODEEDIT_HOST_JNI "Build the JNI library on the host (needs JAVA_HOME)" OFF)
option(CODEEDIT_SANITIZE "Build with ASan/UBSan (host only)" OFF)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 99)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# The tokenizer is CPU bound: never ship the unoptimised -O0 of Gradle debug variants.
if(NOT CODEEDIT_SANITIZE)
  add_compile_options($<$<CONFIG:Debug>:-O2>)
endif()

# ---------------------------------------------------------------- Oniguruma
# Vendored copy (third_party/oniguruma, used by the source ZIP: no network) or, when the folder is absent (CI checkout
# without it), the upstream tag fetched at configure time.
if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/third_party/oniguruma/src/regcomp.c)
  set(ONIG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/oniguruma/src)
else()
  include(FetchContent)
  set(FETCHCONTENT_QUIET OFF)
  FetchContent_Declare(onig_upstream GIT_REPOSITORY https://github.com/kkos/oniguruma.git GIT_TAG v6.9.10 GIT_SHALLOW TRUE)
  FetchContent_GetProperties(onig_upstream)
  if(NOT onig_upstream_POPULATED)
    FetchContent_Populate(onig_upstream)
  endif()
  set(ONIG_DIR ${onig_upstream_SOURCE_DIR}/src)
endif()
include(CheckIncludeFiles)
include(CheckTypeSize)
include(CheckSymbolExists)
check_include_files(alloca.h HAVE_ALLOCA_H)
check_include_files(stdint.h HAVE_STDINT_H)
check_include_files(sys/times.h HAVE_SYS_TIMES_H)
check_include_files(sys/time.h HAVE_SYS_TIME_H)
check_include_files(sys/types.h HAVE_SYS_TYPES_H)
check_include_files(unistd.h HAVE_UNISTD_H)
check_include_files(inttypes.h HAVE_INTTYPES_H)
check_type_size(int SIZEOF_INT)
check_type_size(long SIZEOF_LONG)
check_type_size("long long" SIZEOF_LONG_LONG)
check_type_size("void*" SIZEOF_VOIDP)
if(HAVE_ALLOCA_H)
  check_symbol_exists(alloca "alloca.h" HAVE_ALLOCA)
else()
  check_symbol_exists(alloca "stdlib.h;malloc.h" HAVE_ALLOCA)
endif()
set(PACKAGE onig)
set(PACKAGE_VERSION 6.9.10)
set(VERSION 6.9.10)
set(USE_CRNL_AS_LINE_TERMINATOR 0)
configure_file(${ONIG_DIR}/config.h.cmake.in ${CMAKE_CURRENT_BINARY_DIR}/onig_gen/config.h)

add_library(onig STATIC
  ${ONIG_DIR}/regerror.c ${ONIG_DIR}/regparse.c ${ONIG_DIR}/regext.c ${ONIG_DIR}/regcomp.c ${ONIG_DIR}/regexec.c
  ${ONIG_DIR}/reggnu.c ${ONIG_DIR}/regenc.c ${ONIG_DIR}/regsyntax.c ${ONIG_DIR}/regtrav.c ${ONIG_DIR}/regversion.c
  ${ONIG_DIR}/st.c ${ONIG_DIR}/onig_init.c ${ONIG_DIR}/unicode.c ${ONIG_DIR}/ascii.c ${ONIG_DIR}/utf16_le.c
  ${ONIG_DIR}/unicode_unfold_key.c ${ONIG_DIR}/unicode_fold1_key.c ${ONIG_DIR}/unicode_fold2_key.c ${ONIG_DIR}/unicode_fold3_key.c)
target_include_directories(onig PUBLIC ${ONIG_DIR} PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/onig_gen)
target_compile_definitions(onig PRIVATE HAVE_CONFIG_H)
target_compile_options(onig PRIVATE -w -O2)   # third-party code: no warnings, always optimised

# ---------------------------------------------------------------- engine
set(TM_SOURCES tm_util.cpp tm_json.cpp tm_raw.cpp tm_theme.cpp tm_matcher.cpp tm_onig.cpp tm_registry.cpp tm_rules.cpp
               tm_grammar.cpp tm_tokenize.cpp tm_session.cpp)
add_library(tmcore STATIC ${TM_SOURCES})
target_include_directories(tmcore PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(tmcore PUBLIC onig)
target_compile_options(tmcore PRIVATE -Wall -Wextra)

if(CODEEDIT_SANITIZE)
  foreach(t onig tmcore)
    target_compile_options(${t} PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer -g -O1)
    target_link_options(${t} PUBLIC -fsanitize=address,undefined)
  endforeach()
endif()

# ---------------------------------------------------------------- JNI library
if(ANDROID OR CODEEDIT_HOST_JNI)
  add_library(codeedit_native SHARED jni_bridge.cpp)
  target_link_libraries(codeedit_native PRIVATE tmcore)
  if(ANDROID)
    target_link_libraries(codeedit_native PRIVATE log)
    target_link_options(codeedit_native PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
  else()
    if(NOT DEFINED ENV{JAVA_HOME} AND NOT JAVA_HOME)
      message(FATAL_ERROR "CODEEDIT_HOST_JNI needs JAVA_HOME")
    endif()
    if(NOT JAVA_HOME)
      set(JAVA_HOME $ENV{JAVA_HOME})
    endif()
    target_include_directories(codeedit_native PRIVATE ${JAVA_HOME}/include ${JAVA_HOME}/include/linux)
  endif()
  target_compile_options(codeedit_native PRIVATE -Wall -Wextra)
endif()

# ---------------------------------------------------------------- host test runners
if(CODEEDIT_HOST_TESTS)
  set(NT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native-tests)
  foreach(r run_corpus run_themes smoke_all check_regex)
    add_executable(${r} ${NT}/${r}.cpp)
    target_link_libraries(${r} PRIVATE tmcore)
  endforeach()
endif()
