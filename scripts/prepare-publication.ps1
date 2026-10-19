param([ValidateSet('forge','neoforge')][string]$Loader = 'forge')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = Split-Path -Parent $PSScriptRoot
$distribution = if ($Loader -eq 'forge') { Join-Path $projectRoot 'distribution/forge' } else { Join-Path $projectRoot 'distribution' }
$loaderLabel = $Loader.ToUpperInvariant()
$canvas = New-Object System.Drawing.Bitmap 1600,900,([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($canvas)
$disposables = [System.Collections.Generic.List[System.IDisposable]]::new()
function New-Brush([string]$color) {
    $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml($color))
    $disposables.Add($brush)
    return $brush
}
function New-TextFont([single]$size,[bool]$bold=$false) {
    $style = if ($bold) { [System.Drawing.FontStyle]::Bold } else { [System.Drawing.FontStyle]::Regular }
    $font = New-Object System.Drawing.Font 'Segoe UI',$size,$style,([System.Drawing.GraphicsUnit]::Pixel)
    $disposables.Add($font)
    return $font
}
try {
    $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#0b0e14'))
    $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $red = New-Brush '#f44748'
    $white = New-Brush '#f4f5f7'
    $muted = New-Brush '#aab3c3'
    $green = New-Brush '#91d5ac'
    $panel = New-Brush '#222936'
    $graphics.FillRectangle($red,0,0,1600,6)
    $logo = [System.Drawing.Bitmap]::FromFile((Join-Path $distribution 'brand/renderscope-logo-192x192.png'))
    try { $graphics.DrawImageUnscaled($logo,52,30) } finally { $logo.Dispose() }
    $graphics.DrawString('RenderScope',(New-TextFont 76 $true),$white,264,66)
    $graphics.DrawString("MINECRAFT 1.20.1  /  $loaderLabel  /  CLIENT SIDE",(New-TextFont 23),$muted,270,158)
    $graphics.DrawString("Find the render cost.`nDown to the object.",(New-TextFont 43 $true),$white,(New-Object System.Drawing.RectangleF 64,300,485,150))
    $graphics.FillRectangle($red,64,479,48,4)
    $graphics.DrawString("Measured CPU renderer calls`nPer-instance inspection`nHighlight and world heatmap`nPrivate capture reports",(New-TextFont 25),$muted,(New-Object System.Drawing.RectangleF 64,519,475,205))
    $graphics.DrawString('ACTUAL GAMEPLAY  /  LIVE WORLD HEATMAP',(New-TextFont 19),$green,574,238)
    $screenshot = [System.Drawing.Bitmap]::FromFile((Join-Path $distribution 'gallery/09-world-heatmap.png'))
    try {
        $graphics.FillRectangle($panel,568,272,976,550)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.DrawImage($screenshot,(New-Object System.Drawing.Rectangle 572,276,968,542))
    } finally { $screenshot.Dispose() }
    $graphics.DrawString('F8 to inspect. Follow the cost into your world.',(New-TextFont 23),$white,64,822)
    $canvas.Save((Join-Path $distribution 'brand/renderscope-cover-1600x900.png'),[System.Drawing.Imaging.ImageFormat]::Png)
    Write-Output 'Prepared cover using the original pixel logo and an actual in-game screenshot.'
} finally {
    foreach ($item in $disposables) { $item.Dispose() }
    $graphics.Dispose()
    $canvas.Dispose()
}
