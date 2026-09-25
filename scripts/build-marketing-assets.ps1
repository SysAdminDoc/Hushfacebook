[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$iconSource = Join-Path $root 'concepts/marketing/2026-09-25/selected/icon-master.png'
$heroSource = Join-Path $root 'concepts/marketing/2026-09-25/source/hero-background.png'
$assets = Join-Path $root 'assets'
$iconDirectory = Join-Path $assets 'icons'
$iconOutput = Join-Path $assets 'icon.png'
$heroOutput = Join-Path $assets 'readme-hero.png'
$socialOutput = Join-Path $assets 'github-social-preview.png'
$lockupOutput = Join-Path $assets 'brand-lockup.png'
$darkLockupOutput = Join-Path $assets 'brand-lockup-dark.png'

foreach ($source in @($iconSource, $heroSource)) {
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Missing marketing source: $source"
    }
}

$magick = (Get-Command magick -ErrorAction Stop).Source
$fontList = (& $magick -list font) -join "`n"
foreach ($font in @('Inter-Bold', 'Inter-Regular', 'Inter-SemiBold')) {
    if ($fontList -notmatch "(?m)^\s*Font:\s+$([regex]::Escape($font))\s*$") {
        throw "ImageMagick cannot find the required font: $font"
    }
}

New-Item -ItemType Directory -Force -Path $assets, $iconDirectory | Out-Null

function Invoke-Magick {
    param([Parameter(Mandatory)][string[]] $Arguments)

    & $magick @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "ImageMagick failed with exit code $LASTEXITCODE."
    }
}

Invoke-Magick -Arguments @(
    $iconSource,
    '-alpha', 'on',
    '-resize', '1024x1024!',
    '-channel', 'A', '-fx', 'u<0.02?0:u', '+channel',
    '-strip',
    $iconOutput
)

$iconSizes = @(16, 32, 48, 64, 128, 256, 512, 1024)
foreach ($size in $iconSizes) {
    $variant = Join-Path $iconDirectory "icon-$size.png"
    Invoke-Magick -Arguments @(
        $iconOutput,
        '-filter', 'Lanczos',
        '-resize', "${size}x${size}!",
        '-channel', 'A', '-fx', 'u<0.02?0:u', '+channel',
        '-strip',
        $variant
    )
}

Invoke-Magick -Arguments @(
    $iconOutput,
    '-define', 'icon:auto-resize=256,128,64,48,32,16',
    (Join-Path $assets 'icon.ico')
)

function Build-Lockup {
    param(
        [Parameter(Mandatory)][string] $Output,
        [Parameter(Mandatory)][string] $WordColor,
        [Parameter(Mandatory)][string] $SubheadColor
    )

    Invoke-Magick -Arguments @(
        '-size', '1500x400', 'xc:none',
        '(', $iconOutput, '-resize', '270x270', ')',
        '-gravity', 'northwest', '-geometry', '+48+65', '-composite',
        '(', '-background', 'none', '-fill', $WordColor, '-font', 'Inter-Bold',
        '-pointsize', '108', 'label:HUSHFACEBOOK', ')',
        '-gravity', 'northwest', '-geometry', '+365+92', '-composite',
        '(', '-background', 'none', '-fill', $SubheadColor, '-font', 'Inter-SemiBold',
        '-pointsize', '31', 'label:MORPHE PATCHES FOR FACEBOOK', ')',
        '-gravity', 'northwest', '-geometry', '+370+238', '-composite',
        '-fill', '#1877F2', '-draw', 'rectangle 370,220 915,229',
        '-fill', '#8DBBFF', '-draw', 'rectangle 915,220 1090,229',
        '-strip',
        $Output
    )
}

Build-Lockup -Output $lockupOutput -WordColor '#071B3D' -SubheadColor '#0B4DB8'
Build-Lockup -Output $darkLockupOutput -WordColor '#F8FBFF' -SubheadColor '#BFD8FF'

Invoke-Magick -Arguments @(
    $heroSource,
    '-resize', '1600x900^',
    '-gravity', 'center',
    '-extent', '1600x900',
    '(', $iconOutput, '-resize', '112x112', ')',
    '-gravity', 'northwest', '-geometry', '+96+68', '-composite',
    '(', '-background', 'none', '-fill', '#F8FBFF', '-font', 'Inter-Bold',
    '-pointsize', '52', 'label:HUSHFACEBOOK', ')',
    '-gravity', 'northwest', '-geometry', '+236+86', '-composite',
    '-fill', '#1877F2', '-draw', 'rectangle 238,151 520,158',
    '-fill', '#8DBBFF', '-draw', 'rectangle 520,151 635,158',
    '(', '-background', 'none', '-fill', '#AFCFFF', '-font', 'Inter-SemiBold',
    '-pointsize', '23', 'label:MORPHE PATCHES FOR FACEBOOK', ')',
    '-gravity', 'northwest', '-geometry', '+101+230', '-composite',
    '(', '-background', 'none', '-fill', '#F8FBFF', '-font', 'Inter-Bold',
    '-pointsize', '68', 'label:KEEP THE PEOPLE.', ')',
    '-gravity', 'northwest', '-geometry', '+98+305', '-composite',
    '(', '-background', 'none', '-fill', '#8DBBFF', '-font', 'Inter-Bold',
    '-pointsize', '75', 'label:CUT THE NOISE.', ')',
    '-gravity', 'northwest', '-geometry', '+98+392', '-composite',
    '(', '-background', 'none', '-fill', '#E6F0FF', '-font', 'Inter-Regular',
    '-pointsize', '27', 'label:A quieter Facebook feed with less tracking.', ')',
    '-gravity', 'northwest', '-geometry', '+102+514', '-composite',
    '(', '-background', 'none', '-fill', '#E6F0FF', '-font', 'Inter-Regular',
    '-pointsize', '27', 'label:Save stories and reels when you want them.', ')',
    '-gravity', 'northwest', '-geometry', '+102+555', '-composite',
    '-fill', '#1877F2', '-draw', 'rectangle 104,671 116,683 rectangle 414,671 426,683 rectangle 104,726 116,738 rectangle 414,726 426,738',
    '(', '-background', 'none', '-fill', '#F8FBFF', '-font', 'Inter-SemiBold',
    '-pointsize', '18', 'label:SPONSORED CLUTTER OUT', ')',
    '-gravity', 'northwest', '-geometry', '+132+665', '-composite',
    '(', '-background', 'none', '-fill', '#F8FBFF', '-font', 'Inter-SemiBold',
    '-pointsize', '18', 'label:SHARED LINKS CLEANED', ')',
    '-gravity', 'northwest', '-geometry', '+442+665', '-composite',
    '(', '-background', 'none', '-fill', '#F8FBFF', '-font', 'Inter-SemiBold',
    '-pointsize', '18', 'label:STORY + REEL SAVES', ')',
    '-gravity', 'northwest', '-geometry', '+132+720', '-composite',
    '(', '-background', 'none', '-fill', '#F8FBFF', '-font', 'Inter-SemiBold',
    '-pointsize', '18', 'label:RECOVERY BUILT IN', ')',
    '-gravity', 'northwest', '-geometry', '+442+720', '-composite',
    '-strip',
    $heroOutput
)

Invoke-Magick -Arguments @(
    $heroOutput,
    '-resize', '1280x720!',
    '-gravity', 'center',
    '-crop', '1280x640+0+0',
    '+repage',
    '-strip',
    $socialOutput
)

Write-Host "Built marketing assets in $assets"
