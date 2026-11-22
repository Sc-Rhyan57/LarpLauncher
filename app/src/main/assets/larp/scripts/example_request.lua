local TranslationAPI = loadstring(msd.request("https://raw.githubusercontent.com/Msdoors/Msdoors.gg/refs/heads/main/Scripts/Msdoors/internal/translation-api.lua"))
if TranslationAPI then
    local api = TranslationAPI()
    print("translation api loaded")
    if api and api.Translate then
        print(api.Translate("hello"))
    end
else
    print("failed to fetch translation api")
end
