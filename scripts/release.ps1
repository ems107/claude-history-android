<#
.SYNOPSIS
  Cut a release: bump the version, build a signed APK, tag it, publish it.

.DESCRIPTION
  NEVER run this on your own initiative. Publishing a release is what makes
  every installed copy offer the update, and that is the user's call, always.

  What it does, in order: check the tree is clean and on main, write the new
  version into app/build.gradle.kts, build the signed release APK, stage it
  beside a checksums.txt the app verifies against, commit the bump, create an
  ANNOTATED tag whose message becomes the release notes, push, and hand the
  whole thing to `gh release create`.

.PARAMETER Version
  X.Y.Z. The versionCode is derived from it, so it always moves forward.

.PARAMETER NotesFile
  Markdown for the release notes. Becomes the tag message verbatim.

.PARAMETER DryRun
  Do everything up to the tag, then stop.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$NotesFile,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail($message) {
    Write-Host "release: $message" -ForegroundColor Red
    exit 1
}

if ($Version -notmatch '^\d+\.\d+\.\d+$') { Fail "Version must be X.Y.Z, got '$Version'." }
if (-not (Test-Path $NotesFile)) { Fail "No notes file at $NotesFile." }
if (-not (Test-Path (Join-Path $root 'keystore.properties'))) {
    Fail "No keystore.properties: the release would be unsigned, and an unsigned build cannot install over an installed one."
}

$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne 'main') { Fail "On branch '$branch'. Releases are cut from main." }
if ((git status --porcelain)) { Fail "The working tree is not clean." }

$tag = "v$Version"
if ((git tag --list $tag)) { Fail "Tag $tag already exists." }

# versionCode has to increase monotonically and Android only understands
# integers, so the three numbers are packed into one.
$parts = $Version.Split('.')
$code = [int]$parts[0] * 10000 + [int]$parts[1] * 100 + [int]$parts[2]

Write-Host "release: $Version (versionCode $code)" -ForegroundColor Cyan

$gradleFile = Join-Path $root 'app\build.gradle.kts'
$gradle = Get-Content $gradleFile -Raw
$gradle = [regex]::Replace($gradle, 'versionCode = \d+', "versionCode = $code")
$gradle = [regex]::Replace($gradle, 'versionName = "[^"]+"', "versionName = `"$Version`"")
Set-Content -Path $gradleFile -Value $gradle -NoNewline -Encoding UTF8

Write-Host 'release: building' -ForegroundColor Cyan
& (Join-Path $root 'gradlew.bat') ':app:assembleRelease' --no-daemon
if ($LASTEXITCODE -ne 0) { Fail 'The build failed.' }

$dist = Join-Path $root 'dist'
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Path $dist | Out-Null

$apkName = "claude-history-android-$Version.apk"
$apkPath = Join-Path $dist $apkName
Copy-Item (Join-Path $root 'app\build\outputs\apk\release\app-release.apk') $apkPath

$hash = (Get-FileHash $apkPath -Algorithm SHA256).Hash.ToLower()
Set-Content -Path (Join-Path $dist 'checksums.txt') -Value "$hash  $apkName" -Encoding ASCII

$size = [math]::Round((Get-Item $apkPath).Length / 1MB, 1)
Write-Host "release: $apkName ($size MB), sha256 $hash" -ForegroundColor Cyan

if ($DryRun) {
    Write-Host 'release: dry run, stopping before the tag.' -ForegroundColor Yellow
    exit 0
}

git add $gradleFile
# The very first release carries a version the file already holds, and `git
# commit` with nothing staged is an error rather than a no-op.
$staged = git diff --cached --name-only
if ($staged) {
    git commit -m "Version $Version"
    if ($LASTEXITCODE -ne 0) { Fail 'The version commit failed.' }
} else {
    Write-Host 'release: the file already said that version, nothing to commit.' -ForegroundColor DarkGray
}

# --cleanup=verbatim or git strips every line starting with '#', which is every
# heading the notes have.
git tag -a $tag -F $NotesFile --cleanup=verbatim
if ($LASTEXITCODE -ne 0) { Fail 'Tagging failed.' }

git push origin main
git push origin $tag

gh release create $tag $apkPath (Join-Path $dist 'checksums.txt') --title $tag --notes-from-tag
if ($LASTEXITCODE -ne 0) { Fail 'gh release create failed. The tag is pushed; fix it and create the release by hand.' }

Write-Host "release: $tag published." -ForegroundColor Green
