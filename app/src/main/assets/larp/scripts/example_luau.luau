print("Luau runtime test")

local x = 10 + 5
print("10 + 5 = " .. x)

local t = {}
table.insert(t, "a")
table.insert(t, "b")
table.insert(t, "c")
print("count: " .. #t)

for i, v in ipairs(t) do
    print(i, v)
end

shared.luau_marker = "loaded from .luau"
print("shared.luau_marker = " .. tostring(shared.luau_marker))

local ws = getworkspace()
print("workspace: " .. ws)

msd.writefile("luau_test.txt", "from luau")
print("readback: " .. msd.readfile("luau_test.txt"))

local n = math.random(1, 100)
print("random: " .. n)

local cx, cy = msd.ScreenCenter()
print("center: " .. cx .. ", " .. cy)

local name, ver = identifyexecutor()
print("executor: " .. name .. " " .. ver)
