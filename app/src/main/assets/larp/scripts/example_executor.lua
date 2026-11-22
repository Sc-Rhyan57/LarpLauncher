shared.counter = (shared.counter or 0) + 1
print("shared.counter = " .. tostring(shared.counter))

shared.players = shared.players or {}
table.insert(shared.players, "alice")
table.insert(shared.players, "bob")

for i, name in ipairs(shared.players) do
    print(i, name)
end

getgenv().MY_FLAG = "loaded"

print("MY_FLAG = " .. tostring(MY_FLAG))

local n = math.random(1, 100)
print("random = " .. n)

local x = 10 + 5 * 2
print("math: 10 + 5*2 = " .. x)
print("10 >= 15? " .. tostring(10 >= 15))
print("10 == 10? " .. tostring(10 == 10))

local ws = getworkspace()
print("workspace: " .. ws)

msd.writefile("counter.txt", "count=" .. shared.counter)
print("readback: " .. msd.readfile("counter.txt"))
