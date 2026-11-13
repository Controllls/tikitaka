param(
    [string]$UnsignedApk = 'app/build/outputs/apk/release/app-release-unsigned.apk',
    [string]$OutputApk = 'build/submission/tikitaka_release.apk',
    [string]$KeyDirectory = (Join-Path $env:USERPROFILE '.android-private/tikitaka'),
    [switch]$CreateKey
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$unsigned = if ([IO.Path]::IsPathRooted($UnsignedApk)) { [IO.Path]::GetFullPath($UnsignedApk) } else { [IO.Path]::GetFullPath((Join-Path $root $UnsignedApk)) }
$output = if ([IO.Path]::IsPathRooted($OutputApk)) { [IO.Path]::GetFullPath($OutputApk) } else { [IO.Path]::GetFullPath((Join-Path $root $OutputApk)) }
$keyRoot = [IO.Path]::GetFullPath($KeyDirectory)
if ($keyRoot.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or $keyRoot.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Keep signing keys outside the repository.' }
$jdk = Join-Path $env:USERPROFILE '.gradle/jdks/eclipse_adoptium-25-amd64-windows.2'
$java = Join-Path $jdk 'bin/java.exe'
$keytool = Join-Path $jdk 'bin/keytool.exe'
$buildTools = Join-Path $env:LOCALAPPDATA 'Android/Sdk/build-tools/37.0.0'
$signer = Join-Path $buildTools 'lib/apksigner.jar'
$key = Join-Path $keyRoot 'tikitaka-release.jks'
$passwordFile = Join-Path $keyRoot 'password.dpapi.xml'
foreach ($path in @($unsigned,$java,$keytool,$signer)) { if (-not (Test-Path -LiteralPath $path)) { throw "Missing prerequisite: $path" } }
if ($CreateKey) {
    if ((Test-Path -LiteralPath $key) -or (Test-Path -LiteralPath $passwordFile)) { throw 'Existing key or password file detected. Refusing to overwrite.' }
    New-Item -ItemType Directory -Force -Path $keyRoot | Out-Null
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $keyRoot /inheritance:r /grant:r "*$($sid):(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict key directory access.' }
    $random = New-Object byte[] 36
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    $secure = ConvertTo-SecureString ([Convert]::ToBase64String($random)) -AsPlainText -Force
    $secure | Export-Clixml -LiteralPath $passwordFile
} else {
    if (-not (Test-Path -LiteralPath $key)) { throw 'Signing key missing. Create only with explicit -CreateKey.' }
    $secure = Import-Clixml -LiteralPath $passwordFile
}
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $env:TIKITAKA_SIGN_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    if ($CreateKey) {
        & $keytool -genkeypair -keystore $key -storetype JKS -alias tikitaka-release -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=TikiTaka Release' -storepass:env TIKITAKA_SIGN_PASSWORD -keypass:env TIKITAKA_SIGN_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Key generation failed. Preserve generated files for diagnosis.' }
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
    & $java -jar $signer sign --ks $key --ks-key-alias tikitaka-release --ks-pass env:TIKITAKA_SIGN_PASSWORD --key-pass env:TIKITAKA_SIGN_PASSWORD --out $output $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $java -jar $signer verify --verbose --print-certs $output
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    & $keytool -exportcert -rfc -keystore $key -alias tikitaka-release -storepass:env TIKITAKA_SIGN_PASSWORD -file (Join-Path $keyRoot 'tikitaka-release-public.pem')
    if ($LASTEXITCODE -ne 0) { throw 'Public certificate export failed.' }
    Get-FileHash -LiteralPath $output -Algorithm SHA256
} finally {
    Remove-Item Env:TIKITAKA_SIGN_PASSWORD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}
