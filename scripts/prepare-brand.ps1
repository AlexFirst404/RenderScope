param([string]$Source)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = Split-Path -Parent $PSScriptRoot
$brandRoot = Join-Path $projectRoot 'distribution/brand'
$resourceRoot = Join-Path $projectRoot 'src/main/resources'
$originalPath = [System.IO.Path]::GetFullPath((Join-Path $brandRoot 'renderscope-original.png'))
if ([string]::IsNullOrWhiteSpace($Source)) { $Source = $originalPath }
$Source = (Resolve-Path -LiteralPath $Source).Path
New-Item -ItemType Directory -Force -Path $brandRoot,(Join-Path $resourceRoot 'assets/renderscope/textures/gui') | Out-Null
$original = [System.Drawing.Bitmap]::FromFile($Source)
try {
    if ($Source -ne $originalPath) { Copy-Item -LiteralPath $Source -Destination $originalPath }
    Copy-Item -LiteralPath $Source -Destination (Join-Path $resourceRoot 'assets/renderscope/textures/gui/logo.png')
    foreach ($factor in @(8,16,32)) {
        $target = New-Object System.Drawing.Bitmap ($original.Width*$factor),($original.Height*$factor),([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        try {
            # Exact integer replication: no interpolation, smoothing, palette or alpha changes.
            for ($y=0; $y -lt $original.Height; $y++) {
                for ($x=0; $x -lt $original.Width; $x++) {
                    $pixel = $original.GetPixel($x,$y)
                    for ($dy=0; $dy -lt $factor; $dy++) {
                        for ($dx=0; $dx -lt $factor; $dx++) { $target.SetPixel($x*$factor+$dx,$y*$factor+$dy,$pixel) }
                    }
                }
            }
            $name = 'renderscope-logo-{0}x{1}.png' -f $target.Width,$target.Height
            $target.Save((Join-Path $brandRoot $name),[System.Drawing.Imaging.ImageFormat]::Png)
            for ($y=0; $y -lt $target.Height; $y++) {
                for ($x=0; $x -lt $target.Width; $x++) {
                    if ($target.GetPixel($x,$y).ToArgb() -ne $original.GetPixel([int][Math]::Floor($x/$factor),[int][Math]::Floor($y/$factor)).ToArgb()) {
                        throw "Pixel integrity failed: $name at $x,$y"
                    }
                }
            }
            if ($factor -eq 16) { Copy-Item -LiteralPath (Join-Path $brandRoot $name) -Destination (Join-Path $resourceRoot 'renderscope-logo.png') }
            Write-Output "$name : exact $factor x integer upscale verified pixel-by-pixel"
        } finally { $target.Dispose() }
    }
    foreach ($size in @(512,1024)) {
        $factor = if ($size -eq 512) {16} else {32}
        $scaledWidth = $original.Width*$factor
        $scaledHeight = $original.Height*$factor
        $offsetX = [int](($size-$scaledWidth)/2)
        $offsetY = [int](($size-$scaledHeight)/2)
        $canvas = New-Object System.Drawing.Bitmap $size,$size,([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($canvas)
            try { $graphics.Clear($original.GetPixel(0,0)) } finally { $graphics.Dispose() }
            for ($y=0; $y -lt $scaledHeight; $y++) {
                for ($x=0; $x -lt $scaledWidth; $x++) {
                    $canvas.SetPixel($offsetX+$x,$offsetY+$y,$original.GetPixel([int][Math]::Floor($x/$factor),[int][Math]::Floor($y/$factor)))
                }
            }
            $canvas.Save((Join-Path $brandRoot "renderscope-icon-$size.png"),[System.Drawing.Imaging.ImageFormat]::Png)
            Write-Output "renderscope-icon-$size.png : original pixels at integer scale with matching background padding"
        } finally { $canvas.Dispose() }
    }
    Write-Output "Original $($original.Width)x$($original.Height), all RGBA pixels preserved."
} finally { $original.Dispose() }
