param(
    [Parameter(Mandatory=$true)][string]$MavenPath,
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [ValidateRange(49152,65535)][int]$Port = 55441,
    [string]$Tests = 'Account*Test,Phase2*Test,Phase3*Test,*AdapterTest'
)
$ErrorActionPreference = 'Stop'
$projectPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
if (-not (Test-Path -LiteralPath (Join-Path $projectPath 'pom.xml'))) { throw 'Project pom missing' }
$workPath = Join-Path $projectPath ('target\phase3-postgres-' + [guid]::NewGuid().ToString('N'))
$dataPath = Join-Path $workPath 'data'
New-Item -ItemType Directory -Path $workPath | Out-Null
$started = $false
$oldLocation = Get-Location
$envNames = @('ACCOUNT_DELETION_TEST_OPT_IN','ACCOUNT_DELETION_TEST_JDBC_URL','ACCOUNT_DELETION_TEST_DB_USER','ACCOUNT_DELETION_TEST_DB_PASSWORD')
$saved = @{}
foreach ($name in $envNames) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    & (Join-Path $PostgresBin 'initdb.exe') -D $dataPath -U deletion_test --auth=trust --encoding=UTF8 --no-locale
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic PostgreSQL initialization failed' }
    & (Join-Path $PostgresBin 'pg_ctl.exe') -D $dataPath -l (Join-Path $workPath 'postgres.log') -w -t 30 start -o "-h 127.0.0.1 -p $Port"
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic PostgreSQL start failed' }
    $started = $true
    & (Join-Path $PostgresBin 'createdb.exe') -h 127.0.0.1 -p $Port -U deletion_test nukleus_deletion_test
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic test database creation failed' }
    $env:ACCOUNT_DELETION_TEST_OPT_IN = 'I_UNDERSTAND_LOCAL_SYNTHETIC_ONLY'
    $env:ACCOUNT_DELETION_TEST_JDBC_URL = "jdbc:postgresql://127.0.0.1:$Port/nukleus_deletion_test"
    $env:ACCOUNT_DELETION_TEST_DB_USER = 'deletion_test'
    $env:ACCOUNT_DELETION_TEST_DB_PASSWORD = ''
    Set-Location -LiteralPath $projectPath
    # No SpringBootTest/application launch, deployment, production config or remote database.
    & $MavenPath -o "-Dtest=$Tests" test
    if ($LASTEXITCODE -ne 0) { throw 'Isolated tests failed; inspect target/surefire-reports' }
} finally {
    Set-Location -LiteralPath $oldLocation.Path
    if ($started) {
        & (Join-Path $PostgresBin 'pg_ctl.exe') -D $dataPath -m fast -w -t 30 stop
        if ($LASTEXITCODE -ne 0) { Write-Warning "Stop failed for this synthetic cluster only: $dataPath" }
    }
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    Write-Output "Synthetic cluster files retained for audit: $workPath"
}

