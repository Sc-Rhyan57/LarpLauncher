local line = Drawing.new("Line")
line.X = 10
line.Y = 100
line.X2 = 200
line.Y2 = 100
line.R = 0
line.G = 255
line.B = 0
line.A = 255
line.Thickness = 2
line.Visible = 1

local text = Drawing.new("Text")
text.Text = "LarpLauncher ESP"
text.X = 50
text.Y = 50
text.Size = 16
text.R = 0
text.G = 255
text.B = 0
text.A = 255
text.Visible = 1

local cx, cy = msd.ScreenCenter()
local crosshair = Drawing.new("Line")
crosshair.X = cx - 5
crosshair.Y = cy
crosshair.X2 = cx + 5
crosshair.Y2 = cy
crosshair.R = 255
crosshair.G = 0
crosshair.B = 0
crosshair.A = 255
crosshair.Thickness = 1
crosshair.Visible = 1

print("Drawing objects created (crosshair at center " .. cx .. "," .. cy .. ")")
