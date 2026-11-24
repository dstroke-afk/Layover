$files = @(
  "app\src\main\java\com\intj\layover\navigation\LayoverNavGraph.kt",
  "app\src\main\java\com\intj\layover\ble\LayoverBleConstants.kt"
)

foreach ($f in $files) {
  if (Test-Path $f) {
    $dest = Join-Path ".\upload" $f
    New-Item -ItemType Directory -Path (Split-Path $dest) -Force | Out-Null
    Copy-Item -Path $f -Destination $dest -Force
    Write-Output "Copied: $f"
  } else {
    Write-Warning "Not found: $f"
  }
}