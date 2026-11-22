local changetohorse = false

local PlayerPhysics = msd.Class("", "PlayerPhysics")

local SetBodyType = PlayerPhysics:GetMethod("SetBodyType", 1)
    :cast({
        ret = "void",
        args = {"ptr", "int"}
    })

local FixedUpdate = PlayerPhysics:GetMethod("FixedUpdate", 0)

local fixedAddr = msd.GetMethodAddress(FixedUpdate)
LOGD("FixedUpdate=%p", fixedAddr)

function DrawImGui(width, height)
    local cx, cy = msd.ScreenCenter()
    LOGI("imgui frame center=%d,%d", cx, cy)
end

function PlayerPhysics_FixedUpdate(instance)
    if changetohorse then
        SetBodyType:Call(instance, 1)
        changetohorse = false
    end
    return msd.call_original(instance)
end

msd.hookfunction(FixedUpdate, {
    ret = "void",
    args = {"ptr"}
}, PlayerPhysics_FixedUpdate)

return "Change to horse hook loaded"
