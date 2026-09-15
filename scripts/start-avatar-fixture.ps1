param([Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$fixtureRoot = Join-Path $projectRoot '.local\avatar-live'
$fixtureKc = Join-Path $fixtureRoot 'keycloak'
if (!(Test-Path -LiteralPath (Join-Path $fixtureKc 'lib\lib\main\org.keycloak.keycloak-core-26.7.3.jar'))) {
    throw 'Copy only bin and lib from a Keycloak 26.7.3 distribution into .local/avatar-live/keycloak first.'
}
if (!(Test-Path -LiteralPath (Join-Path $projectRoot 'target\KeycloakQRLogin-2.1.jar'))) { throw 'Build the plugin first.' }
$realmName = 'avatar-acceptance'
$fixturePassword = 'Avatar-Acceptance-Only-2026!'
$base = 'https://localhost:8547'
$mapper = @{ name='avatar-audience'; protocol='openid-connect'; protocolMapper='oidc-audience-mapper'; config=@{ 'included.custom.audience'='avatar-api'; 'access.token.claim'='true'; 'id.token.claim'='false' } }
$clients = @()
foreach ($id in @('avatar-web','avatar-wrong-client','avatar-wrong-audience')) {
    $mappers = @()
    if ($id -ne 'avatar-wrong-audience') { $mappers = @($mapper) }
    $clients += @{ clientId=$id; enabled=$true; publicClient=$true; directAccessGrantsEnabled=$true; standardFlowEnabled=$true; redirectUris=@("$base/*"); webOrigins=@('https://app.example.test'); protocol='openid-connect'; protocolMappers=$mappers }
}
$users = @()
foreach ($name in @('alice','bob')) {
    $users += @{ id="avatar-$name"; username=$name; enabled=$true; email="$name@example.test"; emailVerified=$true; firstName=$name; lastName='Fixture'; credentials=@(@{ type='password'; value=$fixturePassword; temporary=$false }); clientRoles=@{ account=@('manage-account','view-profile') } }
}
$realm = @{ realm=$realmName; enabled=$true; sslRequired='all'; accountTheme='qrlogin'; loginTheme='qrlogin'; internationalizationEnabled=$true; supportedLocales=@('en','zh-CN'); defaultLocale='zh-CN'; clients=$clients; users=$users }
New-Item -ItemType Directory -Force -Path (Join-Path $fixtureKc 'data\import'), (Join-Path $fixtureKc 'providers'), (Join-Path $fixtureKc 'conf') | Out-Null
[IO.File]::WriteAllText((Join-Path $fixtureKc 'data\import\avatar-acceptance-realm.json'), ($realm | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $projectRoot 'target\KeycloakQRLogin-2.1.jar') -Destination (Join-Path $fixtureKc 'providers\KeycloakQRLogin-2.1.jar') -Force
$keyStore = Join-Path $fixtureRoot 'fixture.p12'
if (!(Test-Path -LiteralPath $keyStore)) {
    & 'C:\Program Files\Java\jdk-17.0.2\bin\keytool.exe' -genkeypair -alias fixture -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore $keyStore -storepass $fixturePassword -keypass $fixturePassword -dname 'CN=localhost' -ext 'SAN=dns:localhost,ip:127.0.0.1' -validity 7 -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Fixture certificate generation failed' }
}
$env:JAVA_HOME = $JavaHome
$env:KC_HTTPS_KEY_STORE_PASSWORD = $fixturePassword
$env:KC_BOOTSTRAP_ADMIN_USERNAME = 'avatar-fixture-admin'
$env:KC_BOOTSTRAP_ADMIN_PASSWORD = $fixturePassword
# This fixture listens only on loopback. Its generated key and test credentials never target another instance.
& (Join-Path $fixtureKc 'bin\kc.bat') start-dev --features=declarative-ui --http-host=127.0.0.1 --http-port=8546 --https-port=8547 --https-key-store-file=$keyStore --hostname=$base --http-management-host=127.0.0.1 --http-management-port=9547 --import-realm --spi-theme--static-max-age=-1 --spi-theme--cache-themes=false --spi-theme--cache-templates=false
exit $LASTEXITCODE
