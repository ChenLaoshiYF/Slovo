param([Parameter(Mandatory=$true)][string]$Words,
      [Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference = 'Stop'
$voice = New-Object -ComObject SAPI.SpVoice
$voice.Voice = @($voice.GetVoices() | Where-Object { $_.GetDescription() -like '*Irina*' })[0]
if ($null -eq $voice.Voice -or $voice.Voice.GetDescription() -notlike '*Irina*') { throw 'Install the local SAPI Russian voice before building audio.' }
$voice.Rate = -1
foreach ($entry in (Get-Content -LiteralPath $Words -Encoding utf8)) {
  $key, $russian = $entry.Split([char]9, 2)
  $path = Join-Path $Destination "$key.wav"
  if ((Test-Path -LiteralPath $path) -and (Get-Item -LiteralPath $path).Length -gt 256) { continue }
  $stream = New-Object -ComObject SAPI.SpFileStream
  $stream.Open($path, 3, $false)
  $voice.AudioOutputStream = $stream
  [void]$voice.Speak($russian)
  $stream.Close()
}

