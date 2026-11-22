local function fetchImage(url, savePath)
    local data = msd.request(url)
    if not data then return nil end
    msd.writefile(savePath, data)
    return msd.getcustomasset(savePath)
end

local asset = fetchImage(
    "https://raw.githubusercontent.com/Msdoors/Msdoors.gg/refs/heads/main/Scripts/Msdoors/assets/logo.png",
    "assets/msdoors/logo.png"
)

if asset then
    print("asset ready: " .. tostring(asset))
else
    print("asset fetch failed")
end
