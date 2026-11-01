# 이 스크립트는 .env의 비밀값을 Git에 올리지 않고 현재 창에만 불러와요.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$envFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path $envFile)) {
    throw '.env 파일이 없어요. 먼저 Copy-Item .env.example .env 를 실행해 주세요.'
}

# .env 한 줄씩 읽어 DATABASE_PASSWORD 같은 값을 현재 PowerShell 창에 넣어요.
Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $parts = $line.Split('=', 2)
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
    }
}

if (-not $env:DATABASE_PASSWORD -or $env:DATABASE_PASSWORD.StartsWith('REPLACE_WITH_')) {
    throw '.env 안의 DATABASE_PASSWORD를 길고 안전한 비밀번호로 바꿔 주세요.'
}

# DB를 켜요. 저장된 데이터는 Docker의 kgfinancial-db 볼륨에 남아요.
docker compose --env-file .env up -d

# postgres 프로필을 골라 실제 PostgreSQL DB에 붙여 앱을 실행해요.
$env:SPRING_PROFILES_ACTIVE = 'postgres'
$env:DATABASE_URL = if ($env:DATABASE_URL) { $env:DATABASE_URL } else { 'jdbc:postgresql://localhost:5432/kgdemo' }
$env:DATABASE_USERNAME = if ($env:DATABASE_USERNAME) { $env:DATABASE_USERNAME } else { 'kgdemo' }
mvn spring-boot:run
