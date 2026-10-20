param([string]$Source)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = Split-Path -Parent $PSScriptRoot
$brandRoot = Join-Path $projectRoot 'distribution/brand'
$brandRoots = @($brandRoot, (Join-Path $projectRoot 'distribution/forge/brand'))
$resourceRoot = Join-Path $projectRoot 'src/main/resources'
$guiLogoPath = Join-Path $resourceRoot 'assets/renderscope/textures/gui/logo.png'
$originalPath = [System.IO.Path]::GetFullPath((Join-Path $brandRoot 'renderscope-original.png'))
if ([string]::IsNullOrWhiteSpace($Source)) { $Source = $originalPath }
$Source = (Resolve-Path -LiteralPath $Source).Path
New-Item -ItemType Directory -Force -Path ($brandRoots + @((Split-Path -Parent $guiLogoPath))) | Out-Null

function Save-ExactIntegerScale([System.Drawing.Bitmap]$Original, [int]$Factor, [string]$Destination) {
    $width = $Original.Width * $Factor
    $height = $Original.Height * $Factor
    $pixels = [byte[]]::new($width * $height * 4)
    for ($y = 0; $y -lt $Original.Height; $y++) {
        $row = [byte[]]::new($width * 4)
        for ($x = 0; $x -lt $Original.Width; $x++) {
            $pixel = $Original.GetPixel($x, $y)
            $bgra = [byte[]]@($pixel.B, $pixel.G, $pixel.R, $pixel.A)
            for ($dx = 0; $dx -lt $Factor; $dx++) {
                [Array]::Copy($bgra, 0, $row, ($x * $Factor + $dx) * 4, 4)
            }
        }
        for ($dy = 0; $dy -lt $Factor; $dy++) {
            [Array]::Copy($row, 0, $pixels, ($y * $Factor + $dy) * $row.Length, $row.Length)
        }
    }
    $target = [System.Drawing.Bitmap]::new($width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $rectangle = [System.Drawing.Rectangle]::new(0, 0, $width, $height)
    try {
        $data = $target.LockBits($rectangle, [System.Drawing.Imaging.ImageLockMode]::WriteOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        try {
            if ($data.Stride -ne $width * 4) { throw 'Unexpected destination bitmap stride.' }
            [System.Runtime.InteropServices.Marshal]::Copy($pixels, 0, $data.Scan0, $pixels.Length)
        } finally { $target.UnlockBits($data) }
        $target.Save($Destination, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $target.Dispose() }
    # Reopen the actual saved PNG and compare its complete pixel buffer. There
    # is no canvas, centering, border, alpha conversion, or background fill.
    $saved = [System.Drawing.Bitmap]::FromFile($Destination)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        if ($saved.Width -ne $width -or $saved.Height -ne $height) { throw "Incorrect output size: $Destination" }
        $actual = [byte[]]::new($pixels.Length)
        $data = $saved.LockBits($rectangle, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        try {
            if ($data.Stride -ne $width * 4) { throw 'Unexpected saved bitmap stride.' }
            [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $actual, 0, $actual.Length)
        } finally { $saved.UnlockBits($data) }
        if ([Convert]::ToBase64String($sha.ComputeHash($actual)) -cne [Convert]::ToBase64String($sha.ComputeHash($pixels))) {
            throw "Pixel integrity failed: $Destination"
        }
    } finally { $sha.Dispose(); $saved.Dispose() }
}

$original = [System.Drawing.Bitmap]::FromFile($Source)
try {
    if ($original.Width -ne 24 -or $original.Height -ne 24) { throw 'RenderScope original must be exactly 24x24.' }
    if ($Source -ne $originalPath) { Copy-Item -LiteralPath $Source -Destination $originalPath -Force }
    Copy-Item -LiteralPath $Source -Destination $guiLogoPath -Force
    $assets = @()
    foreach ($factor in @(8, 16, 32, 64)) {
        $size = 24 * $factor
        $name = "renderscope-logo-${size}x${size}.png"
        $path = Join-Path $brandRoot $name
        Save-ExactIntegerScale $original $factor $path
        $assets += [ordered]@{ file = $name; size = @($size, $size); integerScale = $factor; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
        Write-Output "$name : complete original at exact integer scale $factor, zero added pixels"
        if ($size -eq 768) { Copy-Item -LiteralPath $path -Destination (Join-Path $resourceRoot 'renderscope-logo.png') -Force }
    }
    foreach ($size in @(768, 1536)) {
        $name = "renderscope-icon-$size.png"
        $path = Join-Path $brandRoot $name
        Copy-Item -LiteralPath (Join-Path $brandRoot "renderscope-logo-${size}x${size}.png") -Destination $path -Force
        $assets += [ordered]@{ file = $name; size = @($size, $size); integerScale = [int]($size / 24); sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    $integrity = [ordered]@{
        schemaVersion = 2
        sourceFile = 'renderscope-original.png'
        sourceSize = @(24, 24)
        sourceSha256 = (Get-FileHash -LiteralPath $originalPath -Algorithm SHA256).Hash.ToLowerInvariant()
        integerScales = @(8, 16, 32, 64)
        rgbaIdenticalToNearestNeighbor = $true
        publicationIconSizes = @(768, 1536)
        addedPaddingPixels = @(0, 0, 0, 0)
        nativeSourceBackgroundPreserved = $true
        guiLogoSize = @(24, 24)
        modListLogoSize = @(768, 768)
        assets = $assets
    }
    $json = ($integrity | ConvertTo-Json -Depth 6) + "`n"
    [System.IO.File]::WriteAllText((Join-Path $brandRoot 'pixel-integrity.json'), $json, [System.Text.UTF8Encoding]::new($false))
    foreach ($destination in $brandRoots) {
        if ($destination -ne $brandRoot) {
            foreach ($name in @('renderscope-original.png', 'pixel-integrity.json') + @($assets | ForEach-Object { $_.file })) {
                Copy-Item -LiteralPath (Join-Path $brandRoot $name) -Destination (Join-Path $destination $name) -Force
            }
        }
        # Retire only the two old padded outputs. Native source pixels remain.
        foreach ($name in @('renderscope-icon-512.png', 'renderscope-icon-1024.png')) {
            $retired = Join-Path $destination $name
            if (Test-Path -LiteralPath $retired -PathType Leaf) { Remove-Item -LiteralPath $retired }
        }
    }
    Write-Output 'Original 24x24 RGBA pixels preserved; logos/icons have no added canvas or padding.'
} finally { $original.Dispose() }
