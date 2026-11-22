# يبني DiwaniyaKuwait.apk بدون Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
# الأدوات (Java و Android SDK) موجودة في: سطح المكتب\برنامج الرسيفر\tools
$ErrorActionPreference = 'Stop'
# أدوات أندرويد ما تتعامل مع المسارات العربية، فنبني في مجلد مؤقت بمسار إنجليزي
$realProj = $PSScriptRoot
$stage = Join-Path $env:TEMP 'diwanbuild'
$tools = Join-Path $env:TEMP 'diwantools'
if (-not (Test-Path $tools)) {
    New-Item -ItemType Junction -Path $tools -Target (Join-Path $env:USERPROFILE 'Desktop\برنامج الرسيفر\tools') | Out-Null
}
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item "$realProj\AndroidManifest.xml", "$realProj\res", "$realProj\src" $stage -Recurse -Force
if (Test-Path "$realProj\signing.jks") { Copy-Item "$realProj\signing.jks" $stage }
$proj = $stage
$env:JAVA_HOME = (Get-ChildItem "$tools\jdk" -Directory | Select-Object -First 1).FullName
$jbin  = "$env:JAVA_HOME\bin"
$bt    = "$tools\sdk\build-tools\34.0.0"
$jar   = "$tools\sdk\platforms\android-22\android.jar"
$out   = "$proj\build"

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

# آخر نسخة من الشريط تنحط داخل التطبيق كنسخة احتياطية
New-Item -ItemType Directory -Force "$proj\assets" | Out-Null
Copy-Item (Join-Path (Split-Path $realProj -Parent) 'ticker.html') "$proj\assets\ticker.html" -Force
Copy-Item (Join-Path (Split-Path $realProj -Parent) 'saver.html') "$proj\assets\saver.html" -Force
Copy-Item (Join-Path (Split-Path $realProj -Parent) 'source\launcher.html') "$proj\assets\launcher.html" -Force
Copy-Item (Join-Path (Split-Path $realProj -Parent) 'source\aref-ruqaa-bold.woff') "$proj\assets\aref-ruqaa-bold.woff" -Force

& "$bt\aapt2.exe" compile --dir "$proj\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 compile failed' }

& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$proj\AndroidManifest.xml" `
    -A "$proj\assets" --min-sdk-version 21 --target-sdk-version 22 `
    --version-code 20 --version-name 3.4 "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

$src = Get-ChildItem "$proj\src" -Recurse -Filter *.java | ForEach-Object FullName
& "$jbin\javac.exe" -encoding UTF-8 --release 8 -cp $jar -d "$out\classes" @src
if ($LASTEXITCODE) { throw 'javac failed' }

$cls = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --release --min-api 21 --lib $jar --output "$out\dex" @cls
if ($LASTEXITCODE) { throw 'd8 failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\base.apk", 'Update')
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex') | Out-Null
$zip.Dispose()

& "$bt\zipalign.exe" -f -p 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }

# لا تحذف signing.jks: بدونه ما تقدر تحدّث التطبيق فوق النسخة المثبتة
$ks = "$proj\signing.jks"
if (-not (Test-Path $ks)) {
    $ErrorActionPreference = 'Continue'   # keytool يكتب رسائله على stderr
    & "$jbin\keytool.exe" -genkeypair -keystore $ks -alias diwaniya -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass diwaniya2026 -keypass diwaniya2026 -dname 'CN=DiwaniyaKuwait' | Out-Null
    $ErrorActionPreference = 'Stop'
}
Copy-Item $ks "$realProj\signing.jks" -Force
$tmpApk = "$out\DiwaniyaKuwait.apk"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:diwaniya2026 --key-pass pass:diwaniya2026 `
    --min-sdk-version 21 --out $tmpApk "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }

& "$bt\apksigner.bat" verify --min-sdk-version 21 $tmpApk
if ($LASTEXITCODE) { throw 'verify failed' }
$apk = Join-Path (Split-Path $realProj -Parent) 'DiwaniyaKuwait.apk'
Copy-Item $tmpApk $apk -Force
"Built: $apk ($([math]::Round((Get-Item $apk).Length/1KB)) KB)"
