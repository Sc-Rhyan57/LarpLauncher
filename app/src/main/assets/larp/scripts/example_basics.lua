local cx, cy = msd.ScreenCenter()
print("center: " .. tostring(cx) .. ", " .. tostring(cy))

local sw, sh = msd.ScreenSize()
LOGI("screen: %dx%d", sw, sh)

local ok = msd.writefile("test.txt", "hello from lua\n")
print("writefile: " .. tostring(ok))

if msd.isfile("test.txt") then
    local content = msd.readfile("test.txt")
    print("readback: " .. tostring(content))
end

msd.makefolder("assets/icons")

msd.editfile("assets/icons/note.txt", "icon stash\n")

return "msd environment ready"
