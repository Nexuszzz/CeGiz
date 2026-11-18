param(
  [Parameter(Mandatory=$true)][ValidateSet('GEMINI_API_KEY','CEGIZ_CLIENT_TOKEN')][string]$Name,
  [switch]$NoClipboard
)
$ErrorActionPreference='Stop'
$cegizOwnerDir=Join-Path $env:LOCALAPPDATA 'CeGiz/owner'
New-Item -ItemType Directory -Path $cegizOwnerDir -Force | Out-Null
$cegizFile=Join-Path $cegizOwnerDir $(if($Name -eq 'CEGIZ_CLIENT_TOKEN'){'railway-client-token.dpapi'}else{'gemini.dpapi'})
if(-not(Test-Path -LiteralPath $cegizFile)){
  if($Name -eq 'CEGIZ_CLIENT_TOKEN'){
    $cegizBytes=New-Object byte[] 32
    $cegizRng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{$cegizRng.GetBytes($cegizBytes)}finally{$cegizRng.Dispose()}
    $cegizPlain=([BitConverter]::ToString($cegizBytes)).Replace('-','').ToLowerInvariant()
    $cegizSecure=ConvertTo-SecureString -String $cegizPlain -AsPlainText -Force
    $cegizPlain=$null
  }else{
    $cegizOld=Join-Path $PSScriptRoot '.private/gemini.dpapi'
    if(Test-Path -LiteralPath $cegizOld){
      $cegizSecure=(Get-Content -LiteralPath $cegizOld -Raw).Trim() | ConvertTo-SecureString
    }else{
      $cegizSecure=Read-Host 'Pengelola: masukkan API key Gemini untuk Variables Railway' -AsSecureString
    }
  }
  $cegizSecure | ConvertFrom-SecureString | Set-Content -LiteralPath $cegizFile -Encoding utf8
}
if(-not $NoClipboard){
  $cegizSecure=(Get-Content -LiteralPath $cegizFile -Raw).Trim() | ConvertTo-SecureString
  $cegizValue=[Net.NetworkCredential]::new('',$cegizSecure).Password
  try{Set-Clipboard -Value $cegizValue}finally{$cegizValue=$null}
  Write-Output "$Name disalin ke clipboard. Tempel pada Variables Railway milik pengelola."
}else{
  Write-Output 'Konfigurasi pengelola tersedia dalam penyimpanan terenkripsi.'
}
