# يبني ticker.html (اللي ياخذه الرسيفر) من source\ticker-src.html
# يدمج الخط والشعار داخل الملف. بعد التشغيل، الرسيفر يحدّث نفسه خلال دقيقة.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$src  = Join-Path $root 'source'
$html = [IO.File]::ReadAllText("$src\ticker-src.html", [Text.Encoding]::UTF8)
$font = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$src\aref-ruqaa-bold.woff2"))
$html = $html.Replace('__AREF_B64__', $font)
# العطل الرسمية من holidays.json (مرتبة حسب التاريخ)
$hj = Get-Content (Join-Path $root 'holidays.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$hl = @($hj.holidays | Sort-Object from | ForEach-Object { [ordered]@{ name = $_.name; from = $_.from; to = $_.to } })
# واي فاي الضيوف (wifi.json) ومكتبة الباركود
$wf = Get-Content (Join-Path $root 'wifi.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$wobj = [ordered]@{ show = [bool]$wf.show; ssid = $wf.ssid; password = $wf.password; security = $wf.security }
$html = $html.Replace('__WIFI__', (ConvertTo-Json -InputObject $wobj -Compress))
$html = $html.Replace('__QRLIB__', [IO.File]::ReadAllText("$src\qrcode.min.js"))
$html = $html.Replace('__HOLIDAYS__', (ConvertTo-Json -InputObject $hl -Compress -Depth 3))
if (Test-Path "$src\logo.png") {
    $logo = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$src\logo.png"))
    $html = $html.Replace('src="logo.png"', "src=`"data:image/png;base64,$logo`"")
}
$tmp = Join-Path $root 'ticker.html.tmp'
[IO.File]::WriteAllText($tmp, $html, (New-Object Text.UTF8Encoding($false)))
Move-Item $tmp (Join-Path $root 'ticker.html') -Force
"Published: $([math]::Round($html.Length/1KB)) KB"
# شاشة السكون (الساعة الذهبية): source\saver-src.html -> saver.html
$sv = [IO.File]::ReadAllText("$src\saver-src.html", [Text.Encoding]::UTF8)
$sv = $sv.Replace('__AREF_B64__', $font)
$sv = $sv.Replace('__AMIRI_B64__', [Convert]::ToBase64String([IO.File]::ReadAllBytes("$src\amiri-bold.woff2")))
$tmp2 = Join-Path $root 'saver.html.tmp'
[IO.File]::WriteAllText($tmp2, $sv, (New-Object Text.UTF8Encoding($false)))
Move-Item $tmp2 (Join-Path $root 'saver.html') -Force
"Saver: $([math]::Round($sv.Length/1KB)) KB"