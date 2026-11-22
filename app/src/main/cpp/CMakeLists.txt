cmake_minimum_required(VERSION 3.22.1)
project(larpdevkit LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

include(FetchContent)
set(FETCHCONTENT_QUIET FALSE)

FetchContent_Declare(
    lua
    URL https://www.lua.org/ftp/lua-5.4.7.tar.gz
    URL_HASH SHA256=9fbf5e28ef86c69858f6d3d34eccc32e911c1a28b4120ff3e84aaa70cfbf1e30
)

FetchContent_GetProperties(lua)
if(NOT lua_POPULATED)
    FetchContent_Populate(lua)
endif()

set(LUA_SRC
    ${lua_SOURCE_DIR}/lapi.c
    ${lua_SOURCE_DIR}/lauxlib.c
    ${lua_SOURCE_DIR}/lbaselib.c
    ${lua_SOURCE_DIR}/lcode.c
    ${lua_SOURCE_DIR}/lcorolib.c
    ${lua_SOURCE_DIR}/lctype.c
    ${lua_SOURCE_DIR}/ldblib.c
    ${lua_SOURCE_DIR}/ldebug.c
    ${lua_SOURCE_DIR}/ldo.c
    ${lua_SOURCE_DIR}/ldump.c
    ${lua_SOURCE_DIR}/lfunc.c
    ${lua_SOURCE_DIR}/lgc.c
    ${lua_SOURCE_DIR}/linit.c
    ${lua_SOURCE_DIR}/liolib.c
    ${lua_SOURCE_DIR}/llex.c
    ${lua_SOURCE_DIR}/lmathlib.c
    ${lua_SOURCE_DIR}/lmem.c
    ${lua_SOURCE_DIR}/loadlib.c
    ${lua_SOURCE_DIR}/lobject.c
    ${lua_SOURCE_DIR}/lopcodes.c
    ${lua_SOURCE_DIR}/loslib.c
    ${lua_SOURCE_DIR}/lparser.c
    ${lua_SOURCE_DIR}/lstate.c
    ${lua_SOURCE_DIR}/lstring.c
    ${lua_SOURCE_DIR}/lstrlib.c
    ${lua_SOURCE_DIR}/ltable.c
    ${lua_SOURCE_DIR}/ltablib.c
    ${lua_SOURCE_DIR}/ltm.c
    ${lua_SOURCE_DIR}/lundump.c
    ${lua_SOURCE_DIR}/lutf8lib.c
    ${lua_SOURCE_DIR}/lvm.c
    ${lua_SOURCE_DIR}/lzio.c
)

add_library(lua_static STATIC ${LUA_SRC})
target_compile_options(lua_static PRIVATE -DLUA_USE_IOS=0 -DLUA_USE_DLOPEN=0 -DLUA_COMPAT_5_3 -O2 -fno-rtti -fno-exceptions -fvisibility=hidden)
target_include_directories(lua_static PUBLIC ${lua_SOURCE_DIR})

FetchContent_Declare(
    luau
    GIT_REPOSITORY https://github.com/luau-lang/luau.git
    GIT_TAG 0.644
    GIT_SHALLOW TRUE
)

FetchContent_GetProperties(luau)
if(NOT luau_POPULATED)
    FetchContent_Populate(luau)
endif()

set(LUAU_VM_SRC
    ${luau_SOURCE_DIR}/VM/src/lapi.cpp
    ${luau_SOURCE_DIR}/VM/src/lbaselib.cpp
    ${luau_SOURCE_DIR}/VM/src/lbitlib.cpp
    ${luau_SOURCE_DIR}/VM/src/lbuffer.cpp
    ${luau_SOURCE_DIR}/VM/src/lbuflib.cpp
    ${luau_SOURCE_DIR}/VM/src/lcode.cpp
    ${luau_SOURCE_DIR}/VM/src/lcorolib.cpp
    ${luau_SOURCE_DIR}/VM/src/ldblib.cpp
    ${luau_SOURCE_DIR}/VM/src/ldo.cpp
    ${luau_SOURCE_DIR}/VM/src/lfunc.cpp
    ${luau_SOURCE_DIR}/VM/src/lgc.cpp
    ${luau_SOURCE_DIR}/VM/src/linit.cpp
    ${luau_SOURCE_DIR}/VM/src/lmathlib.cpp
    ${luau_SOURCE_DIR}/VM/src/lmem.cpp
    ${luau_SOURCE_DIR}/VM/src/lnumprint.cpp
    ${luau_SOURCE_DIR}/VM/src/lnumlib.cpp
    ${luau_SOURCE_DIR}/VM/src/lobject.cpp
    ${luau_SOURCE_DIR}/VM/src/loslib.cpp
    ${luau_SOURCE_DIR}/VM/src/lperf.cpp
    ${luau_SOURCE_DIR}/VM/src/lstate.cpp
    ${luau_SOURCE_DIR}/VM/src/lstring.cpp
    ${luau_SOURCE_DIR}/VM/src/lstrlib.cpp
    ${luau_SOURCE_DIR}/VM/src/ltable.cpp
    ${luau_SOURCE_DIR}/VM/src/ltablib.cpp
    ${luau_SOURCE_DIR}/VM/src/ltm.cpp
    ${luau_SOURCE_DIR}/VM/src/ludata.cpp
    ${luau_SOURCE_DIR}/VM/src/lutf8lib.cpp
    ${luau_SOURCE_DIR}/VM/src/lvm.cpp
    ${luau_SOURCE_DIR}/VM/src/lvmexecute.cpp
    ${luau_SOURCE_DIR}/VM/src/lvmload.cpp
    ${luau_SOURCE_DIR}/VM/src/lvmutils.cpp
    ${luau_SOURCE_DIR}/VM/src/ltable.cpp
)

set(LUAU_COMPILER_SRC
    ${luau_SOURCE_DIR}/Compiler/src/Builtins.cpp
    ${luau_SOURCE_DIR}/Compiler/src/Compiler.cpp
    ${luau_SOURCE_DIR}/Compiler/src/ConstantFolding.cpp
    ${luau_SOURCE_DIR}/Compiler/src/ConstantPropagation.cpp
    ${luau_SOURCE_DIR}/Compiler/src/CostModel.cpp
    ${luau_SOURCE_DIR}/Compiler/src/CodeAllocator.cpp
    ${luau_SOURCE_DIR}/Compiler/src/CodeGen.cpp
    ${luau_SOURCE_DIR}/Compiler/src/CodeGenA64.cpp
    ${luau_SOURCE_DIR}/Compiler/src/CodeGenX64.cpp
    ${luau_SOURCE_DIR}/Compiler/src/CodeGenLower.cpp
    ${luau_SOURCE_DIR}/Compiler/src/CodeGenLowerA64.cpp
    ${luau_SOURCE_DIR}/Compiler/src/CodeGenLowerX64.cpp
    ${luau_SOURCE_DIR}/Compiler/src/Compilation.cpp
    ${luau_SOURCE_DIR}/Compiler/src/BytecodeBuilder.cpp
    ${luau_SOURCE_DIR}/Compiler/src/Parser.cpp
    ${luau_SOURCE_DIR}/Compiler/src/Lexer.cpp
    ${luau_SOURCE_DIR}/Compiler/src/ParseData.cpp
    ${luau_SOURCE_DIR}/Compiler/src/ParseResultTable.cpp
    ${luau_SOURCE_DIR}/Compiler/src/TableShape.cpp
    ${luau_SOURCE_DIR}/Compiler/src/TypeInfer.cpp
    ${luau_SOURCE_DIR}/Compiler/src/ToString.cpp
    ${luau_SOURCE_DIR}/Compiler/src/Utils.cpp
)

set(LUAU_AST_SRC
    ${luau_SOURCE_DIR}/Ast/src/Ast.cpp
    ${luau_SOURCE_DIR}/Ast/src/Location.cpp
    ${luau_SOURCE_DIR}/Ast/src/Lexer.cpp
    ${luau_SOURCE_DIR}/Ast/src/ParseInfo.cpp
    ${luau_SOURCE_DIR}/Ast/src/Parser.cpp
    ${luau_SOURCE_DIR}/Ast/src/StringUtils.cpp
    ${luau_SOURCE_DIR}/Ast/src/isqlwrite.cpp
)

add_library(luau_vm STATIC ${LUAU_VM_SRC})
target_compile_options(luau_vm PRIVATE -DLUA_USE_LONGJMP=1 -DLUA_USE_ASSERT=0 -O2 -fno-rtti -fno-exceptions -fvisibility=hidden)
target_include_directories(luau_vm PUBLIC ${luau_SOURCE_DIR}/VM/include)

add_library(luau_compiler STATIC ${LUAU_COMPILER_SRC} ${LUAU_AST_SRC})
target_compile_options(luau_compiler PRIVATE -DLUA_USE_LONGJMP=1 -O2 -fno-rtti -fno-exceptions -fvisibility=hidden)
target_include_directories(luau_compiler PUBLIC ${luau_SOURCE_DIR}/Compiler/include ${luau_SOURCE_DIR}/Ast/include ${luau_SOURCE_DIR}/VM/include)
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
target_include_directories(larplua PRIVATE ${lua_SOURCE_DIR})
target_link_libraries(larplua ${log-lib} lua_static dl)
target_link_options(larplua PRIVATE -Wl,-z,max-page-size=16384)

add_library(larpluau SHARED larpluau.cpp)
target_compile_options(larpluau PRIVATE -O2 -fno-rtti -fno-exceptions -fvisibility=hidden -DLUA_USE_LONGJMP=1)
target_include_directories(larpluau PRIVATE ${luau_SOURCE_DIR}/VM/include ${luau_SOURCE_DIR}/Compiler/include ${luau_SOURCE_DIR}/Ast/include)
target_link_libraries(larpluau ${log-lib} luau_vm luau_compiler dl)
target_link_options(larpluau PRIVATE -Wl,-z,max-page-size=16384)

target_link_options(larpdevkit PRIVATE -Wl,-z,max-page-size=16384)
target_link_options(fusion PRIVATE -Wl,-z,max-page-size=16384)
target_link_options(nextbep PRIVATE -Wl,-z,max-page-size=16384)
target_link_options(larpui PRIVATE -Wl,-z,max-page-size=16384)
