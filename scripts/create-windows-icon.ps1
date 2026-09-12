param(
    [string]$Source = "app/src/main/resources/org/mbali/assets/mbaliscope-icon.png",
    [string]$Destination = "app/src/main/resources/org/mbali/assets/mbaliscope-icon.ico"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$destinationPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Destination))
$temporaryPng = Join-Path $env:TEMP "mbaliscope-icon-256.png"

$sourceBitmap = $null
$targetBitmap = $null
$graphics = $null
$writer = $null

try {
    $sourceBitmap = [System.Drawing.Bitmap]::FromFile($sourcePath)
    $targetBitmap = [System.Drawing.Bitmap]::new(
        256,
        256,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    $graphics = [System.Drawing.Graphics]::FromImage($targetBitmap)
    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.Clear([System.Drawing.Color]::Transparent)
    $graphics.DrawImage($sourceBitmap, 0, 0, 256, 256)
    $targetBitmap.Save($temporaryPng, [System.Drawing.Imaging.ImageFormat]::Png)

    [byte[]]$pngBytes = [System.IO.File]::ReadAllBytes($temporaryPng)
    $stream = [System.IO.File]::Create($destinationPath)
    $writer = [System.IO.BinaryWriter]::new($stream)

    # ICONDIR
    $writer.Write([uint16]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]1)

    # ICONDIRENTRY. A zero width/height byte means 256 pixels in the ICO format.
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]32)
    $writer.Write([uint32]$pngBytes.Length)
    $writer.Write([uint32]22)
    $writer.Write($pngBytes, 0, $pngBytes.Length)
}
finally {
    if ($writer) { $writer.Dispose() }
    if ($graphics) { $graphics.Dispose() }
    if ($targetBitmap) { $targetBitmap.Dispose() }
    if ($sourceBitmap) { $sourceBitmap.Dispose() }
    if (Test-Path -LiteralPath $temporaryPng) {
        Remove-Item -LiteralPath $temporaryPng
    }
}

Write-Output "Windows icon created: $destinationPath"
