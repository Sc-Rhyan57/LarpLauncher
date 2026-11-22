# Larp Lua Runtime (`liblarplua.so`)

O Larp Launcher embarca um interpretador Lua 5.4 nativo exposto para mods via `liblarplua.so`. Mods como o msdoors podem chamar a runtime por P/Invoke em vez de embalar MoonSharp.

Inicializacao:

```csharp
using msdoors.Lua;
int rc = LarpLuaNative.Init(scriptsDir, scriptsDir, Screen.width, Screen.height, 1f);
LarpLuaNative.Run("print('hi')", "demo");
LarpLuaNative.Tick(Time.deltaTime, Screen.width, Screen.height);
LarpLuaNative.Shutdown();
```

O `GameActivity` do launcher chama `LarpLua.init(...)` ao abrir o Unity, instala scripts de `assets/larp/scripts/` em `filesDir/larp/scripts/` e roda um tick de ~60 fps chamando `OnUpdate(dt)` e `DrawImGui(w,h)` globais em cada frame.

## Globals

| Nome | Tipo | Observacao |
|---|---|---|
| `print(...)` | funcao | loga em logcat (tag `LarpLua`) e em `UnityEngine.Debug.Log` via il2cpp |
| `warn(...)` | funcao | loga como warning |
| `LOGD`, `LOGI`, `LOGW`, `LOGE` | funcao | estilo `printf` (`%d %s %x %p %f %c %b`) |
| `loadstring(code, chunk?)` | funcao | retorna `fn` ou `nil, err` |
| `require(name)` | funcao | procura `<scripts>/<name>.lua` |
| `request(urlOrTable)` | funcao | HTTP/1.1 simples (string) ou tabela (`Url`, `Method`, `Body`, `Headers`) |
| `Color(r, g, b, a?)` | funcao | empacota como `0xAARRGGBB` |
| `json.encode(v)`, `json.decode(s)` | funcao | JSON minimalista |
| `Draw` | tabela | espelhada para `ApiMenu.LuaCanvas` (obsoleto; prefira overlay nativo) |
| `table.*` | biblioteca padrao Lua 5.4 | `insert`, `remove`, `concat`, `unpack`, `sort` |

## Tabela `msd`

| Campo | Assinatura |
|---|---|
| `msd.Version` | string |
| `msd.Class(ns, name)` | `LuaClass` |
| `msd.GetMethodAddress(method)` | `LuaPtr` |
| `msd.hookfunction(method, sig, callback)` | `LuaHook` (Dobby) |
| `msd.hookfunction_addr(addr, sig, callback)` | `LuaHook` (endereco cru) |
| `msd.call_original(...)` | chamada para o trampoline do hook atual |
| `msd.ptr(value)` | `LuaPtr` |
| `msd.writefile(path, content)` | bool |
| `msd.readfile(path)` | string ou nil |
| `msd.isfile(path)` | bool |
| `msd.editfile(path, content)` | bool (alias de writefile) |
| `msd.makefolder(path)` | bool |
| `msd.listfolder(path)` | array de nomes |
| `msd.removefile(path)` | bool |
| `msd.getcustomasset(path)` | base64 do arquivo para uso em `image` do overlay |
| `msd.ScreenSize()` | `w, h` |
| `msd.ScreenCenter()` | `cx, cy` |
| `msd.Screen()` | tabela `{width, height, cx, cy, scale}` |
| `msd.WorldToScreen(x, y, z)` | `sx, sy, visible` (placeholder ate ponte de camera) |
| `msd.Time()` | segundos monotonicos |
| `msd.FPS()` | int |
| `msd.Ping()` | int |
| `msd.Notify(text)` | toast |
| `msd.SetClipboard(text)` | bool |
| `msd.Color(r, g, b, a?)` | int empacotado |
| `msd.GameState()` | string |
| `msd.LocalName()` | string |
| `msd.JSONEncode(v)` | string |
| `msd.JSONDecode(s)` | valor Lua |
| `msd.request(...)` | alias global |
| `msd.loadstring(...)` | alias global |

## `LuaClass`

| Campo | Observacao |
|---|---|
| `:GetMethod(name, argc?)` | retorna `LuaMethod` ou nil |
| `:Methods()` | tabela `{name, argc}` |
| `:GetFieldOffset(name)` | int ou nil |
| `:StaticGet(name, type)` | valor (type = "int", "float", "ptr", "string", etc.) |
| `:StaticSet(name, type, value)` | bool |
| `:Hook(name, argc, sig, callback)` | atalho para `msd.hookfunction` |
| `:Replace(name, argc, sig, callback)` | alias de `:Hook` |

## `LuaMethod`

| Campo | Observacao |
|---|---|
| `:Call(...)` | invoca via thunk C de 8 args inteiros + 8 doubles |
| `:cast(sig)` | reescreve `ret`/`args` |
| `:GetAddress()` | `LuaPtr` |
| `:GetName()`, `:GetArgCount()`, `:GetIsStatic()` | metadados |
| `__call` | atalho para `:Call` |

## `LuaPtr`

Leitura/escrita por offset: `read_int`, `read_uint`, `read_long`, `read_short`, `read_byte`, `read_bool`, `read_float`, `read_double`, `read_ptr`, `read_string`. Escrita: `write_int`, `write_long`, `write_short`, `write_byte`, `write_bool`, `write_float`, `write_double`, `write_ptr`.

Operadores: `__add`, `__sub`, `__eq`. Campos: `address`, `value`. Metodos: `is_null()`, `eq(other)`, `add(n)`, `sub(n)`.

## `LuaHook`

| Campo |
|---|---|
| `:disable()` |
| `:enable()` |
| `:is_enabled()` |
| `:call_original(...)` |
| `.Target`, `.Original`, `.Enabled` |

## Assinatura `sig`

```lua
{
  ret = "void" | "int" | "uint" | "long" | "ulong" | "short" | "ushort" |
        "byte" | "sbyte" | "bool" | "float" | "double" | "string" | "ptr",
  args = { "ptr", "int", "float", ... }
}
```

Se `sig` for omitido, `ret` vira `"ptr"` e `args` recebe `"ptr"` para cada argumento (incluindo `this` para metodos de instancia).

## Ponte para il2cpp

A runtime resolve `il2cpp_*` de `libil2cpp.so` via `dlsym` e anexa a thread atual. Classes/metodos/campos sao acessiveis pelos bindings acima. Hooks usam `DobbyHook` de `libdobby.so`.

## Ponte para Larp UI

`larplua_register_larpui(sendFn, drawFn)` registra ponteiros C `larpui_send(json)` e `larpui_draw(layer, node, ints, count, texts)`. Scripts que chamam `Draw.Line`, `Draw.Rect`, etc. vao enviar comandos JSON para `LarpOverlay` (processo `:game`).
