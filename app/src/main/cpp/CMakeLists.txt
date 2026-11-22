cmake_minimum_required(VERSION 3.22.1)
project(larpdevkit LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(LUA_TARBALL ${CMAKE_BINARY_DIR}/_deps/lua-5.4.7.tar.gz)
set(LUA_DIR ${CMAKE_BINARY_DIR}/_deps/lua-src)

if(NOT EXISTS ${LUA_DIR}/lapi.c)
    message(STATUS "Downloading Lua 5.4.7...")
    file(DOWNLOAD
        https://www.lua.org/ftp/lua-5.4.7.tar.gz
        ${LUA_TARBALL}
        EXPECTED_HASH SHA256=9fbf5e28ef86c69858f6d3d34eccc32e911c1a28b4120ff3e84aaa70cfbf1e30
        SHOW_PROGRESS
    )
    message(STATUS "Extracting Lua 5.4.7...")
    file(ARCHIVE_EXTRACT INPUT ${LUA_TARBALL} DESTINATION ${CMAKE_BINARY_DIR}/_deps)
    file(RENAME ${CMAKE_BINARY_DIR}/_deps/lua-5.4.7 ${LUA_DIR})
endif()

set(LUA_INCLUDE ${LUA_DIR})
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

set(LUAU_VM_SRC
    ${LUAU_DIR}/VM/src/lapi.cpp
    ${LUAU_DIR}/VM/src/laux.cpp
    ${LUAU_DIR}/VM/src/lbaselib.cpp
    ${LUAU_DIR}/VM/src/lbitlib.cpp
    ${LUAU_DIR}/VM/src/lbuffer.cpp
    ${LUAU_DIR}/VM/src/lbuflib.cpp
    ${LUAU_DIR}/VM/src/lbuiltins.cpp
    ${LUAU_DIR}/VM/src/lcorolib.cpp
    ${LUAU_DIR}/VM/src/ldblib.cpp
    ${LUAU_DIR}/VM/src/ldebug.cpp
    ${LUAU_DIR}/VM/src/ldo.cpp
    ${LUAU_DIR}/VM/src/lfunc.cpp
    ${LUAU_DIR}/VM/src/lgc.cpp
    ${LUAU_DIR}/VM/src/lgcdebug.cpp
    ${LUAU_DIR}/VM/src/linit.cpp
    ${LUAU_DIR}/VM/src/lmathlib.cpp
    ${LUAU_DIR}/VM/src/lmem.cpp
    ${LUAU_DIR}/VM/src/lnumprint.cpp
    ${LUAU_DIR}/VM/src/lobject.cpp
    ${LUAU_DIR}/VM/src/loslib.cpp
    ${LUAU_DIR}/VM/src/lperf.cpp
    ${LUAU_DIR}/VM/src/lstate.cpp
    ${LUAU_DIR}/VM/src/lstring.cpp
    ${LUAU_DIR}/VM/src/lstrlib.cpp
    ${LUAU_DIR}/VM/src/ltable.cpp
    ${LUAU_DIR}/VM/src/ltablib.cpp
    ${LUAU_DIR}/VM/src/ltm.cpp
    ${LUAU_DIR}/VM/src/ludata.cpp
    ${LUAU_DIR}/VM/src/lutf8lib.cpp
    ${LUAU_DIR}/VM/src/lvmexecute.cpp
    ${LUAU_DIR}/VM/src/lvmload.cpp
    ${LUAU_DIR}/VM/src/lvmutils.cpp
)

set(LUAU_COMPILER_SRC
    ${LUAU_DIR}/Compiler/src/BytecodeBuilder.cpp
    ${LUAU_DIR}/Compiler/src/Compiler.cpp
    ${LUAU_DIR}/Compiler/src/Builtins.cpp
    ${LUAU_DIR}/Compiler/src/BuiltinFolding.cpp
    ${LUAU_DIR}/Compiler/src/ConstantFolding.cpp
    ${LUAU_DIR}/Compiler/src/CostModel.cpp
    ${LUAU_DIR}/Compiler/src/TableShape.cpp
    ${LUAU_DIR}/Compiler/src/Types.cpp
    ${LUAU_DIR}/Compiler/src/ValueTracking.cpp
    ${LUAU_DIR}/Compiler/src/lcode.cpp
)

set(LUAU_AST_SRC
    ${LUAU_DIR}/Ast/src/Ast.cpp
    ${LUAU_DIR}/Ast/src/Confusables.cpp
    ${LUAU_DIR}/Ast/src/Lexer.cpp
    ${LUAU_DIR}/Ast/src/Location.cpp
    ${LUAU_DIR}/Ast/src/Parser.cpp
    ${LUAU_DIR}/Ast/src/StringUtils.cpp
    ${LUAU_DIR}/Ast/src/TimeTrace.cpp
)

add_library(luau_vm STATIC ${LUAU_VM_SRC})
target_compile_options(luau_vm PRIVATE -DLUA_USE_LONGJMP=1 -DLUA_USE_ASSERT=0 -O2 -fno-rtti -fno-exceptions -fvisibility=hidden)
target_include_directories(luau_vm PUBLIC ${LUAU_DIR}/VM/include)

add_library(luau_compiler STATIC ${LUAU_COMPILER_SRC} ${LUAU_AST_SRC})
target_compile_options(luau_compiler PRIVATE -DLUA_USE_LONGJMP=1 -O2 -fno-rtti -fno-exceptions -fvisibility=hidden)
target_include_directories(luau_compiler PUBLIC
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
