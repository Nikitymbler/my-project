# Temporary negative/lifecycle HTTP tests for Arena StreamToEarn local bridge.
# Compatible with Windows PowerShell 5.1 and PowerShell 7+.
# Does not print the real token. Does not send valid match-changing events.

$ErrorActionPreference = 'Continue'
$BaseUrl = 'http://127.0.0.1:8765'
$Token = 'arena-local-test-2026'
$Script:Passed = 0
$Script:Failed = 0
$Script:Results = New-Object System.Collections.Generic.List[object]

function Get-HeaderValue {
    param($Headers, [string]$Name)
    if ($null -eq $Headers) { return $null }
    if ($Headers -is [System.Net.WebHeaderCollection]) {
        return $Headers[$Name]
    }
    foreach ($key in $Headers.Keys) {
        if ([string]::Equals([string]$key, $Name, [System.StringComparison]::OrdinalIgnoreCase)) {
            $val = $Headers[$key]
            if ($val -is [System.Array]) { return ($val -join ', ') }
            return [string]$val
        }
    }
    return $null
}

function Invoke-ArenaRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Body = $null,
        [hashtable]$Headers = $null,
        [int]$ContentLengthOverride = -1
    )

    $uri = $BaseUrl + $Path
    $result = [ordered]@{
        StatusCode    = -1
        Body          = ''
        ContentType   = $null
        CacheControl  = $null
        Allow         = $null
        Error         = $null
        Json          = $null
    }

    try {
        if ($PSVersionTable.PSVersion.Major -ge 6) {
            $params = @{
                Uri             = $uri
                Method          = $Method
                TimeoutSec      = 10
                SkipHttpErrorCheck = $true
            }
            $hdr = @{}
            if ($Headers) {
                foreach ($k in $Headers.Keys) { $hdr[$k] = $Headers[$k] }
            }
            if ($null -ne $Body) {
                $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
                if ($ContentLengthOverride -ge 0) {
                    if ($ContentLengthOverride -gt $bytes.Length) {
                        $pad = New-Object byte[] $ContentLengthOverride
                        [Array]::Copy($bytes, $pad, $bytes.Length)
                        for ($i = $bytes.Length; $i -lt $ContentLengthOverride; $i++) { $pad[$i] = 0x41 }
                        $bytes = $pad
                    }
                    elseif ($ContentLengthOverride -lt $bytes.Length) {
                        $trimmed = New-Object byte[] $ContentLengthOverride
                        [Array]::Copy($bytes, $trimmed, $ContentLengthOverride)
                        $bytes = $trimmed
                    }
                }
                $params['Body'] = $bytes
                $params['ContentType'] = 'text/plain; charset=utf-8'
            }
            if ($hdr.Count -gt 0) { $params['Headers'] = $hdr }

            $resp = Invoke-WebRequest @params
            $result.StatusCode = [int]$resp.StatusCode
            $result.Body = [string]$resp.Content
            $result.ContentType = Get-HeaderValue $resp.Headers 'Content-Type'
            $result.CacheControl = Get-HeaderValue $resp.Headers 'Cache-Control'
            $result.Allow = Get-HeaderValue $resp.Headers 'Allow'
        }
        else {
            # PowerShell 5.1
            [System.Net.ServicePointManager]::Expect100Continue = $false
            $request = [System.Net.HttpWebRequest]::Create($uri)
            $request.Method = $Method.ToUpperInvariant()
            $request.Timeout = 10000
            $request.ReadWriteTimeout = 10000
            $request.AutomaticDecompression = [System.Net.DecompressionMethods]::None

            if ($Headers) {
                foreach ($k in $Headers.Keys) {
                    if ($k -ieq 'Content-Type') {
                        $request.ContentType = [string]$Headers[$k]
                    }
                    else {
                        $request.Headers[$k] = [string]$Headers[$k]
                    }
                }
            }

            if ($null -ne $Body -and $Method.ToUpperInvariant() -ne 'GET' -and $Method.ToUpperInvariant() -ne 'HEAD') {
                $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
                if (-not $request.ContentType) {
                    $request.ContentType = 'text/plain; charset=utf-8'
                }
                if ($ContentLengthOverride -ge 0) {
                    $request.ContentLength = $ContentLengthOverride
                    if ($ContentLengthOverride -gt $bytes.Length) {
                        $pad = New-Object byte[] $ContentLengthOverride
                        [Array]::Copy($bytes, $pad, $bytes.Length)
                        for ($i = $bytes.Length; $i -lt $ContentLengthOverride; $i++) { $pad[$i] = 0x41 }
                        $bytes = $pad
                    }
                    elseif ($ContentLengthOverride -lt $bytes.Length) {
                        $trimmed = New-Object byte[] $ContentLengthOverride
                        [Array]::Copy($bytes, $trimmed, $ContentLengthOverride)
                        $bytes = $trimmed
                    }
                }
                else {
                    $request.ContentLength = $bytes.Length
                }
                $stream = $request.GetRequestStream()
                try {
                    $stream.Write($bytes, 0, $bytes.Length)
                }
                finally {
                    $stream.Close()
                }
            }
            # GET/HEAD: do not set a request body / ContentLength.

            try {
                $response = [System.Net.HttpWebResponse]$request.GetResponse()
            }
            catch [System.Net.WebException] {
                if ($_.Exception.Response) {
                    $response = [System.Net.HttpWebResponse]$_.Exception.Response
                }
                else {
                    throw
                }
            }

            try {
                $result.StatusCode = [int]$response.StatusCode
                $result.ContentType = $response.ContentType
                $result.CacheControl = $response.Headers['Cache-Control']
                $result.Allow = $response.Headers['Allow']
                $reader = New-Object System.IO.StreamReader($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
                try {
                    $result.Body = $reader.ReadToEnd()
                }
                finally {
                    $reader.Close()
                }
            }
            finally {
                $response.Close()
            }
        }
    }
    catch {
        $ex = $_.Exception
        $handled = $false

        # PS7 HttpResponseException
        if ($ex.GetType().FullName -match 'HttpResponseException' -or ($ex.Response -and $ex.Response.StatusCode)) {
            try {
                if ($ex.Response.StatusCode) {
                    $result.StatusCode = [int]$ex.Response.StatusCode
                    $handled = $true
                }
                if ($ex.Response.Content) {
                    $result.Body = [string]$ex.Response.Content
                }
                if ($ex.Response.Headers) {
                    $result.ContentType = Get-HeaderValue $ex.Response.Headers 'Content-Type'
                    $result.CacheControl = Get-HeaderValue $ex.Response.Headers 'Cache-Control'
                    $result.Allow = Get-HeaderValue $ex.Response.Headers 'Allow'
                }
            }
            catch { }
        }

        # Nested WebException
        $webEx = $ex
        while ($webEx -and -not ($webEx -is [System.Net.WebException])) {
            $webEx = $webEx.InnerException
        }
        if (-not $handled -and $webEx -is [System.Net.WebException] -and $webEx.Response) {
            $resp = [System.Net.HttpWebResponse]$webEx.Response
            try {
                $result.StatusCode = [int]$resp.StatusCode
                $result.ContentType = $resp.ContentType
                $result.CacheControl = $resp.Headers['Cache-Control']
                $result.Allow = $resp.Headers['Allow']
                $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
                try { $result.Body = $reader.ReadToEnd() } finally { $reader.Close() }
                $handled = $true
            }
            finally {
                $resp.Close()
            }
        }

        if (-not $handled) {
            $result.Error = $ex.Message
        }
    }

    if ($result.Body) {
        try {
            $result.Json = $result.Body | ConvertFrom-Json -ErrorAction Stop
        }
        catch {
            $result.Json = $null
        }
    }

    return [pscustomobject]$result
}

function Assert-Test {
    param(
        [string]$Name,
        [bool]$Ok,
        [string]$Detail,
        [int]$Status = -1
    )
    if ($Ok) {
        $Script:Passed++
        $mark = 'PASS'
    }
    else {
        $Script:Failed++
        $mark = 'FAIL'
    }
    $Script:Results.Add([pscustomobject]@{
            Test   = $Name
            Result = $mark
            Status = $(if ($Status -ge 0) { $Status } else { '-' })
            Detail = $Detail
        }) | Out-Null
    Write-Host ("[{0}] {1} (status={2}) {3}" -f $mark, $Name, $(if ($Status -ge 0) { $Status } else { 'n/a' }), $Detail)
}

Write-Host "=== Arena S2E HTTP bridge negative tests ==="
Write-Host ("Base URL: {0}" -f $BaseUrl)
Write-Host ("Token configured: {0}" -f (-not [string]::IsNullOrEmpty($Token)))
Write-Host ""

# 1. Health
$r1 = Invoke-ArenaRequest -Method GET -Path '/arena/health'
$ok1 = ($r1.StatusCode -eq 200) -and
    ($null -ne $r1.Json) -and
    ($r1.Json.ok -eq $true) -and
    ($r1.Json.service -eq 'arena-of-nations-s2e') -and
    ($r1.ContentType -match 'application/json') -and
    ($r1.CacheControl -match 'no-store')
Assert-Test -Name '1 GET /arena/health' -Ok $ok1 -Status $r1.StatusCode -Detail $(
    if ($ok1) { 'ok/service/headers OK' } else { "body=$($r1.Body); err=$($r1.Error)" }
)

# 2. Chat without token
$r2 = Invoke-ArenaRequest -Method POST -Path '/arena/chat' -Body 'security_probe|||!ru'
$ok2 = ($r2.StatusCode -eq 401) -and ($null -ne $r2.Json) -and ($r2.Json.accepted -eq $false) -and ($r2.Json.reason -eq 'unauthorized')
Assert-Test -Name '2 POST /arena/chat no token' -Ok $ok2 -Status $r2.StatusCode -Detail $(
    if ($ok2) { 'unauthorized OK' } else { "body=$($r2.Body); err=$($r2.Error)" }
)

# 3. Gift wrong token
$r3 = Invoke-ArenaRequest -Method POST -Path '/arena/gift' -Body 'security_probe|||10|||security-wrong-token' -Headers @{ 'X-Arena-Token' = 'wrong-token' }
$ok3 = ($r3.StatusCode -eq 401) -and ($null -ne $r3.Json) -and ($r3.Json.accepted -eq $false) -and ($r3.Json.reason -eq 'unauthorized')
Assert-Test -Name '3 POST /arena/gift wrong token' -Ok $ok3 -Status $r3.StatusCode -Detail $(
    if ($ok3) { 'unauthorized OK' } else { "body=$($r3.Body); err=$($r3.Error)" }
)

# 4. GET /arena/chat -> 405 Allow POST
$r4 = Invoke-ArenaRequest -Method GET -Path '/arena/chat'
$ok4 = ($r4.StatusCode -eq 405) -and ($r4.Allow -match 'POST')
Assert-Test -Name '4 GET /arena/chat' -Ok $ok4 -Status $r4.StatusCode -Detail $(
    if ($ok4) { "Allow=$($r4.Allow)" } else { "Allow=$($r4.Allow); body=$($r4.Body); err=$($r4.Error)" }
)

# 5. GET /arena/gift -> 405 Allow POST
$r5 = Invoke-ArenaRequest -Method GET -Path '/arena/gift'
$ok5 = ($r5.StatusCode -eq 405) -and ($r5.Allow -match 'POST')
Assert-Test -Name '5 GET /arena/gift' -Ok $ok5 -Status $r5.StatusCode -Detail $(
    if ($ok5) { "Allow=$($r5.Allow)" } else { "Allow=$($r5.Allow); body=$($r5.Body); err=$($r5.Error)" }
)

# 6. POST /arena/health -> 405 Allow GET
$r6 = Invoke-ArenaRequest -Method POST -Path '/arena/health' -Body ''
$ok6 = ($r6.StatusCode -eq 405) -and ($r6.Allow -match 'GET')
Assert-Test -Name '6 POST /arena/health' -Ok $ok6 -Status $r6.StatusCode -Detail $(
    if ($ok6) { "Allow=$($r6.Allow)" } else { "Allow=$($r6.Allow); body=$($r6.Body); err=$($r6.Error)" }
)

# 7. Body exactly 1001 ASCII bytes
$body1001 = ('A' * 1001)
$r7 = Invoke-ArenaRequest -Method POST -Path '/arena/chat' -Body $body1001 -Headers @{ 'X-Arena-Token' = $Token }
$ok7 = ($r7.StatusCode -eq 413)
Assert-Test -Name '7 POST /arena/chat 1001-byte body' -Ok $ok7 -Status $r7.StatusCode -Detail $(
    if ($ok7) { 'payload_too_large OK (body not printed)' } else { "bodyLenSent=1001; resp=$($r7.Body); err=$($r7.Error)" }
)

# 8. Invalid gift payload
$r8 = Invoke-ArenaRequest -Method POST -Path '/arena/gift' -Body 'invalid-payload' -Headers @{ 'X-Arena-Token' = $Token }
$leak = $false
if ($r8.Body) {
    if ($r8.Body -match 'Exception|StackTrace|java\.|com\.nikita|sun\.net|at com\.') { $leak = $true }
}
$ok8 = ($r8.StatusCode -eq 400) -and ($null -ne $r8.Json) -and ($r8.Json.accepted -eq $false) -and
    (-not [string]::IsNullOrWhiteSpace([string]$r8.Json.reason)) -and (-not $leak)
Assert-Test -Name '8 POST /arena/gift invalid payload' -Ok $ok8 -Status $r8.StatusCode -Detail $(
    if ($ok8) { "reason=$($r8.Json.reason)" } else { "body=$($r8.Body); leak=$leak; err=$($r8.Error)" }
)

# 9. Content-Length > 1000 with small actual body attempt / oversized declared length
# Send a 1001-byte body (ensures server-side limit) AND declare Content-Length 1001.
# Separately try Content-Length header larger than body if PS allows.
$oversizedDeclared = $false
try {
    # Prefer real oversized body via declared Content-Length 1500 with padded payload (still invalid match event).
    $padBody = ('X' * 50)
    $r9 = Invoke-ArenaRequest -Method POST -Path '/arena/gift' -Body $padBody -Headers @{ 'X-Arena-Token' = $Token } -ContentLengthOverride 1500
    $oversizedDeclared = $true
}
catch {
    # Fallback: 1001-byte body already covered in test 7; mark as skipped-equivalent via body size
    $r9 = Invoke-ArenaRequest -Method POST -Path '/arena/gift' -Body ('B' * 1001) -Headers @{ 'X-Arena-Token' = $Token }
}
$ok9 = ($r9.StatusCode -eq 413)
Assert-Test -Name '9 POST /arena/gift Content-Length>1000' -Ok $ok9 -Status $r9.StatusCode -Detail $(
    if ($ok9) {
        if ($oversizedDeclared) { '413 with Content-Length override 1500' } else { '413 via 1001-byte body fallback' }
    }
    else { "body=$($r9.Body); err=$($r9.Error)" }
)

# 10. Unknown endpoint — observe only
$r10 = Invoke-ArenaRequest -Method GET -Path '/arena/unknown'
$observed = "status=$($r10.StatusCode)"
if ($r10.Error) { $observed += "; error=$($r10.Error)" }
elseif ($r10.Body) {
    $snippet = $r10.Body
    if ($snippet.Length -gt 120) { $snippet = $snippet.Substring(0, 120) + '...' }
    $observed += "; body=$snippet"
}
# Always PASS for observation (not a hard requirement)
Assert-Test -Name '10 GET /arena/unknown (observe)' -Ok $true -Status $r10.StatusCode -Detail $observed

# 11. StreamToEarn body-auth chat without separator
$r11 = Invoke-ArenaRequest -Method POST -Path '/arena/streamtoearn/chat' -Body 'invalid'
$ok11 = ($r11.StatusCode -eq 400) -and ($null -ne $r11.Json) -and ($r11.Json.accepted -eq $false)
Assert-Test -Name '11 POST /arena/streamtoearn/chat no separator' -Ok $ok11 -Status $r11.StatusCode -Detail $(
    if ($ok11) { "reason=$($r11.Json.reason)" } else { "body=$($r11.Body); err=$($r11.Error)" }
)

# 12. StreamToEarn body-auth chat wrong token (no valid match event)
$r12 = Invoke-ArenaRequest -Method POST -Path '/arena/streamtoearn/chat' -Body 'wrong-token|||security_test|||!ru'
$ok12 = ($r12.StatusCode -eq 401) -and ($null -ne $r12.Json) -and ($r12.Json.accepted -eq $false) -and ($r12.Json.reason -eq 'unauthorized')
Assert-Test -Name '12 POST /arena/streamtoearn/chat wrong token' -Ok $ok12 -Status $r12.StatusCode -Detail $(
    if ($ok12) { 'unauthorized OK' } else { "body=$($r12.Body); err=$($r12.Error)" }
)

# 13. GET StreamToEarn chat -> 405
$r13 = Invoke-ArenaRequest -Method GET -Path '/arena/streamtoearn/chat'
$ok13 = ($r13.StatusCode -eq 405) -and ($r13.Allow -match 'POST')
Assert-Test -Name '13 GET /arena/streamtoearn/chat' -Ok $ok13 -Status $r13.StatusCode -Detail $(
    if ($ok13) { "Allow=$($r13.Allow)" } else { "Allow=$($r13.Allow); body=$($r13.Body); err=$($r13.Error)" }
)

# 14. StreamToEarn gift with inner payload > 1000 bytes (invalid oversized; does not enqueue a real gift)
# Body shape: <token>||| + 1001 ASCII bytes. Uses configured token only for auth path; payload is not a valid gift.
$inner1001 = ('Z' * 1001)
$compatGiftBody = $Token + '|||' + $inner1001
$r14 = Invoke-ArenaRequest -Method POST -Path '/arena/streamtoearn/gift' -Body $compatGiftBody
$ok14 = ($r14.StatusCode -eq 413)
Assert-Test -Name '14 POST /arena/streamtoearn/gift payload>1000' -Ok $ok14 -Status $r14.StatusCode -Detail $(
    if ($ok14) { 'payload_too_large OK (oversized body not printed)' } else { "resp=$($r14.Body); err=$($r14.Error)" }
)

# 15. JSON chat malformed
$r15 = Invoke-ArenaRequest -Method POST -Path '/arena/streamtoearn/chat' -Body '{not-json'
$ok15 = ($r15.StatusCode -eq 400) -and ($null -ne $r15.Json) -and ($r15.Json.accepted -eq $false) -and ($r15.Json.reason -eq 'malformed_json')
Assert-Test -Name '15 JSON chat malformed' -Ok $ok15 -Status $r15.StatusCode -Detail $(
    if ($ok15) { 'malformed_json OK' } else { "body=$($r15.Body); err=$($r15.Error)" }
)

# 16. JSON chat wrong token (no match-changing event)
$r16Body = '{"token":"wrong-token","viewerId":"security_json","message":"!ru"}'
$r16 = Invoke-ArenaRequest -Method POST -Path '/arena/streamtoearn/chat' -Body $r16Body
$ok16 = ($r16.StatusCode -eq 401) -and ($null -ne $r16.Json) -and ($r16.Json.reason -eq 'unauthorized')
Assert-Test -Name '16 JSON chat wrong token' -Ok $ok16 -Status $r16.StatusCode -Detail $(
    if ($ok16) { 'unauthorized OK' } else { "body=$($r16.Body); err=$($r16.Error)" }
)

# 17. JSON gift missing viewerId
$r17Body = '{"token":"wrong-token","coins":10}'
$r17 = Invoke-ArenaRequest -Method POST -Path '/arena/streamtoearn/gift' -Body $r17Body
$ok17 = ($r17.StatusCode -eq 400) -and ($null -ne $r17.Json) -and ($r17.Json.accepted -eq $false) -and ($r17.Json.reason -eq 'missing_field')
Assert-Test -Name '17 JSON gift missing viewerId' -Ok $ok17 -Status $r17.StatusCode -Detail $(
    if ($ok17) { 'missing_field OK' } else { "body=$($r17.Body); err=$($r17.Error)" }
)

# 18. JSON gift non-numeric coins — fails before acceptGiftPayload (safe with real token)
$r18Body = '{"token":"' + $Token + '","viewerId":"security_json","coins":"abc"}'
$r18 = Invoke-ArenaRequest -Method POST -Path '/arena/streamtoearn/gift' -Body $r18Body
$ok18 = ($r18.StatusCode -eq 400) -and ($null -ne $r18.Json) -and ($r18.Json.accepted -eq $false) -and ($r18.Json.reason -eq 'missing_field')
Assert-Test -Name '18 JSON gift non-numeric coins' -Ok $ok18 -Status $r18.StatusCode -Detail $(
    if ($ok18) { 'missing_field OK' } else { "body=$($r18.Body); err=$($r18.Error)" }
)

# 19. JSON gift oversized body (>1260 bytes), does not enqueue
$r19Body = '{"token":"x","viewerId":"y","coins":1,"pad":"' + ('Q' * 1300) + '"}'
$r19 = Invoke-ArenaRequest -Method POST -Path '/arena/streamtoearn/gift' -Body $r19Body
$ok19 = ($r19.StatusCode -eq 413)
Assert-Test -Name '19 JSON gift oversized body' -Ok $ok19 -Status $r19.StatusCode -Detail $(
    if ($ok19) { 'payload_too_large OK (body not printed)' } else { "resp=$($r19.Body); err=$($r19.Error)" }
)

Write-Host ""
Write-Host "=== Summary ==="
$Script:Results | Format-Table -AutoSize Test, Result, Status, Detail | Out-String | Write-Host
Write-Host ("passed={0} failed={1}" -f $Script:Passed, $Script:Failed)

if ($Script:Failed -gt 0) {
    exit 1
}
exit 0
