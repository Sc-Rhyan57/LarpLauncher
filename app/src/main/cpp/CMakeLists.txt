cmake_minimum_required(VERSION 3.22.1)
project(larpdevkit LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(LUA_TARBALL ${CMAKE_BINARY_DIR}/_deps/lua-5.4.7.tar.gz)
set(LUA_DIR ${CMAKE_BINARY_DIR}/_deps/lua-src)

if(NOT EXISTS ${LUA_DIR}/src/lapi.c)
    message(STATUS "Downloading Lua 5.4.7...")
    file(DOWNLOAD
        https://www.lua.org/ftp/lua-5.4.7.tar.gz
        ${LUA_TARBALL}
        EXPECTED_HASH SHA256=9fbf5e28ef86c69858f6d3d34eccc32e911c1a28b4120ff3e84aaa70cfbf1e30
        SHOW_PROGRESS
    )
    message(STATUS "Extracting Lua 5.4.7...")
    file(REMOVE_RECURSE ${LUA_DIR} ${CMAKE_BINARY_DIR}/_deps/lua-5.4.7)
    file(ARCHIVE_EXTRACT INPUT ${LUA_TARBALL} DESTINATION ${CMAKE_BINARY_DIR}/_deps)
    file(RENAME ${CMAKE_BINARY_DIR}/_deps/lua-5.4.7 ${LUA_DIR})
endif()

set(LUA_INCLUDE ${LUA_DIR}/src)
file(GLOB LUA_SRC ${LUA_INCLUDE}/*.c)
list(REMOVE_ITEM LUA_SRC
    ${LUA_INCLUDE}/lua.c
    ${LUA_INCLUDE}/luac.c
    ${LUA_INCLUDE}/onelua.c
)

add_library(lua_static STATIC ${LUA_SRC})
target_compile_options(lua_static PRIVATE -DLUA_USE_IOS=0 -DLUA_USE_DLOPEN=0 -DLUA_COMPAT_5_3 -O2 -fno-rtti -fno-exceptions -fvisibility=hidden)
target_include_directories(lua_static PUBLIC ${LUA_INCLUDE})

set(LUAU_DIR ${CMAKE_BINARY_DIR}/_deps/luau-src)

if(NOT EXISTS ${LUAU_DIR}/VM/src/lapi.cpp)
    message(STATUS "Cloning Luau 0.644...")
    find_package(Git REQUIRED)
    execute_process(
        COMMAND ${GIT_EXECUTABLE} clone --depth 1 --branch 0.644 https://github.com/luau-lang/luau.git ${LUAU_DIR}
        RESULT_VARIABLE LUAU_CLONE_RESULT
    )
    if(NOT LUAU_CLONE_RESULT EQUAL 0)
        message(FATAL_ERROR "Failed to clone Luau repository")
    endif()
endif()

# Glob em vez de lista fixa: evita esquecer arquivos (ex: Allocator.cpp, Cst.cpp)
file(GLOB LUAU_VM_SRC ${LUAU_DIR}/VM/src/*.cpp)
file(GLOB LUAU_COMPILER_SRC ${LUAU_DIR}/Compiler/src/*.cpp)
file(GLOB LUAU_AST_SRC ${LUAU_DIR}/Ast/src/*.cpp)
set(LUAU_COMMON_INC ${LUAU_DIR}/Common/include)

add_library(luau_vm STATIC ${LUAU_VM_SRC})
target_compile_options(luau_vm PRIVATE -DLUA_USE_LONGJMP=1 -DLUA_USE_ASSERT=0 -O2 -fno-rtti -fno-exceptions -fvisibility=hidden)
target_include_directories(luau_vm PUBLIC ${LUAU_DIR}/VM/include ${LUAU_COMMON_INC})

add_library(luau_compiler STATIC ${LUAU_COMPILER_SRC} ${LUAU_AST_SRC})
# O Compiler do Luau usa try/throw, então precisa de exceptions habilitadas
target_compile_options(luau_compiler PRIVATE -DLUA_USE_LONGJMP=1 -O2 -fno-rtti -fexceptions -fvisibility=hidden)
target_include_directories(luau_compiler PUBLIC
    ${LUAU_COMMON_INC}
    ${LUAU_DIR}/Compiler/include
    ${LUAU_DIR}/Ast/include
    ${LUAU_DIR}/VM/include
    ${LUAU_DIR}/VM/src
    ${LUAU_DIR}/Compiler/src
    ${LUAU_DIR}/Ast/src
)
target_link_libraries(luau_compiler luau_vm)

find_library(log-lib log)

add_library(larpdevkit SHARED il2cpp_dump.cpp)
target_compile_options(larpdevkit PRIVATE -O2 -fno-rtti)
target_link_libraries(larpdevkit ${log-lib})

add_library(fusion SHARED fusion.cpp)
target_compile_options(fusion PRIVATE -O2 -fno-rtti -fno-exceptions -fvisibility=hidden)
target_link_libraries(fusion ${log-lib} dl)

add_library(nextbep SHARED nextbep.cpp)
target_link_libraries(nextbep dl)
target_compile_options(nextbep PRIVATE -O2 -fno-rtti -fno-exceptions -fvisibility=hidden)

add_library(larpui SHARED larpui.cpp)
target_compile_options(larpui PRIVATE -O2 -fno-rtti -fno-exceptions)
target_link_libraries(larpui ${log-lib})

add_library(larplua SHARED larplua.cpp)
target_compile_options(larplua PRIVATE -O2 -fno-rtti -fno-exceptions -fvisibility=hidden -DLUA_USE_DLOPEN=0)
target_include_directories(larplua PRIVATE ${LUA_INCLUDE})
target_link_libraries(larplua ${log-lib} lua_static dl)
target_link_options(larplua PRIVATE -Wl,-z,max-page-size=16384)

add_library(larpluau SHARED larpluau.cpp)
target_compile_options(larpluau PRIVATE -O2 -fno-rtti -fno-exceptions -fvisibility=hidden -DLUA_USE_LONGJMP=1)
target_include_directories(larpluau PRIVATE
    ${LUAU_COMMON_INC}
    ${LUAU_DIR}/VM/include
    ${LUAU_DIR}/Compiler/include
    ${LUAU_DIR}/Ast/include
)
target_link_libraries(larpluau ${log-lib} luau_vm luau_compiler dl)
target_link_options(larpluau PRIVATE -Wl,-z,max-page-size=16384)

target_link_options(larpdevkit PRIVATE -Wl,-z,max-page-size=16384)
target_link_options(fusion PRIVATE -Wl,-z,max-page-size=16384)
target_link_options(nextbep PRIVATE -Wl,-z,max-page-size=16384)
target_link_options(larpui PRIVATE -Wl,-z,max-page-size=16384)
