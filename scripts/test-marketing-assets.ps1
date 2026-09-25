[CmdletBinding()]
param([string] $Root)

$ErrorActionPreference = 'Stop'

$root = if ([string]::IsNullOrWhiteSpace($Root)) {
    Split-Path -Parent $PSScriptRoot
} else {
    (Resolve-Path -LiteralPath $Root).Path
}
$readmePath = Join-Path $root 'README.md'
$magick = (Get-Command magick -ErrorAction Stop).Source
$failures = [System.Collections.Generic.List[string]]::new()

function Assert-True {
    param(
        [Parameter(Mandatory)][bool] $Condition,
        [Parameter(Mandatory)][string] $Message
    )

    if (-not $Condition) {
        $failures.Add($Message)
    }
}

function Image-Property {
    param(
        [Parameter(Mandatory)][string] $Path,
        [Parameter(Mandatory)][string] $Format
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $failures.Add("Missing asset: $Path")
        return ''
    }

    $value = & $magick identify -format $Format $Path
    if ($LASTEXITCODE -ne 0) {
        $failures.Add("ImageMagick could not inspect: $Path")
        return ''
    }
    return [string]$value
}

$expected = @{
    'assets/icon.png' = '1024x1024'
    'assets/readme-hero.png' = '1600x900'
    'assets/github-social-preview.png' = '1280x640'
    'assets/brand-lockup.png' = '1500x400'
    'assets/brand-lockup-dark.png' = '1500x400'
}

foreach ($entry in $expected.GetEnumerator()) {
    $path = Join-Path $root $entry.Key
    $dimensions = Image-Property -Path $path -Format '%wx%h'
    Assert-True ($dimensions -eq $entry.Value) "$($entry.Key) is $dimensions, expected $($entry.Value)."
}

foreach ($size in @(16, 32, 48, 64, 128, 256, 512, 1024)) {
    $relative = "assets/icons/icon-$size.png"
    $variantPath = Join-Path $root $relative
    $dimensions = Image-Property -Path $variantPath -Format '%wx%h'
    Assert-True ($dimensions -eq "${size}x${size}") "$relative is $dimensions, expected ${size}x${size}."
    $last = $size - 1
    foreach ($point in @('0,0', "$last,0", "0,$last", "$last,$last")) {
        $pixel = Image-Property -Path $variantPath -Format "%[pixel:p{$point}]"
        Assert-True ($pixel -match 's?rgba?\([^)]*,0\)$|a\(0\)|/0(?:\D|$)|none') "$relative has a non-transparent corner at $point`: $pixel"
    }
}

$icon = Join-Path $root 'assets/icon.png'
$corner = Image-Property -Path $icon -Format '%[pixel:p{0,0}]'
$center = Image-Property -Path $icon -Format '%[pixel:p{512,512}]'
$transparentPixel = 's?rgba?\([^)]*,0\)$|a\(0\)|/0(?:\D|$)|none'
Assert-True ($corner -match $transparentPixel) "assets/icon.png does not have a transparent top-left corner: $corner"
Assert-True ($center -notmatch $transparentPixel) "assets/icon.png has a transparent center: $center"

$readme = Get-Content -LiteralPath $readmePath -Raw
$firstLine = ($readme -split "`r?`n" | Where-Object { $_.Trim().Length -gt 0 } | Select-Object -First 1)
Assert-True ($firstLine -match '^!\[Hushfacebook\..*\]\(assets/readme-hero\.png\)$') 'README.md must start with the evergreen Hushfacebook hero.'
Assert-True (([regex]::Matches($readme, 'assets/readme-hero\.png')).Count -eq 1) 'README.md must reference the hero exactly once.'
Assert-True (([regex]::Matches($readme, 'https://ko-fi\.com/X8K126YVER')).Count -eq 1) 'README.md must contain the canonical Ko-fi link exactly once.'

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host 'Marketing asset checks passed.'
