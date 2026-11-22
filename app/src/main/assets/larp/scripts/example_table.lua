local players = {}

table.insert(players, "alice")
table.insert(players, "bob")
table.insert(players, "carol")

print("count: " .. tostring(#players))

for i, name in ipairs(players) do
    print(i, name)
end

table.remove(players, 2)
print("after remove: " .. table.concat(players, ", "))

local scores = { alice = 10, bob = 5, carol = 20 }
local json = msd.JSONEncode(scores)
print("json: " .. json)

local back = msd.JSONDecode(json)
print("alice back: " .. tostring(back.alice))
