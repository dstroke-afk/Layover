$outFile = Join-Path (Get-Location) "upload.txt"

Get-ChildItem -Path ".\app\src" -Recurse -File |
  Where-Object { $_.FullName -notmatch "\\(build|\.gradle|\.git)\\" } |
  Sort-Object FullName |
  ForEach-Object {
    $_.FullName.Replace((Get-Location).Path + "\", "")
  } |
  Set-Content -Path $outFile -Encoding UTF8

Write-Output "Wrote file list to $outFile"