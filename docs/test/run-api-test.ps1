# QSXManager 业务接口完整测试脚本（PowerShell 7+）
# 启动应用后执行：真实 HTTP 调用全部业务接口，记录 请求信息 + 响应信息 到 api-test-results.json
# 用法：powershell -ExecutionPolicy Bypass -File docs/test/run-api-test.ps1
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$results = @()
$ts = Get-Date -Format 'yyyyMMddHHmmss'
$outFile = Join-Path $PSScriptRoot 'api-test-results.json'

# 统一调用函数：记录 名称/方法/URL/Authorization/请求体/状态码/响应体
function Invoke-Api {
    param([string]$Name, [string]$Method, [string]$Url, [hashtable]$Hdrs = @{}, $Body = $null)
    $status = ''
    $content = ''
    try {
        $params = @{
            Uri = $Url; Method = $Method; Headers = $Hdrs
            UseBasicParsing = $true; TimeoutSec = 30; SkipHttpErrorCheck = $true
        }
        if ($null -ne $Body) {
            $params.Body = $Body
            $params.ContentType = 'application/json'
        }
        $resp = Invoke-WebRequest @params
        $status = [int]$resp.StatusCode
        $content = $resp.Content
    } catch {
        $status = 'ERROR'
        $content = $_.Exception.Message
    }
    $script:results += [pscustomobject]@{
        name     = $Name
        method   = $Method
        url      = $Url
        auth     = if ($Hdrs.ContainsKey('Authorization')) { $Hdrs['Authorization'] } else { '' }
        body     = if ($null -ne $Body) { [string]$Body } else { '' }
        status   = $status
        response = $content
    }
    return $content
}

# ---------- 1. 认证中心 ----------
$regEmail = "t_report_$ts@test.com"
$regBody = @{ email = $regEmail; password = 'abc123'; nickname = '报告测试用户' } | ConvertTo-Json
Invoke-Api -Name '注册' -Method 'POST' -Url "$base/auth/register" -Body $regBody

$loginResp = Invoke-Api -Name '登录(超管admin@qsx.com)' -Method 'POST' -Url "$base/auth/login" -Body (@{ email = 'admin@qsx.com'; password = 'admin123' } | ConvertTo-Json)
$adminToken = 'Bearer ' + (($loginResp | ConvertFrom-Json).data.token)

$userLoginResp = Invoke-Api -Name '登录(测试用户)' -Method 'POST' -Url "$base/auth/login" -Body (@{ email = $regEmail; password = 'abc123' } | ConvertTo-Json)
$userToken = 'Bearer ' + (($userLoginResp | ConvertFrom-Json).data.token)

Invoke-Api -Name '当前用户信息' -Method 'GET' -Url "$base/auth/me" -Hdrs @{ Authorization = $userToken }

Invoke-Api -Name '修改密码(改)' -Method 'POST' -Url "$base/auth/change-password" -Hdrs @{ Authorization = $userToken } -Body (@{ oldPassword = 'abc123'; newPassword = 'abc124' } | ConvertTo-Json)
Invoke-Api -Name '修改密码(改回)' -Method 'POST' -Url "$base/auth/change-password" -Hdrs @{ Authorization = $userToken } -Body (@{ oldPassword = 'abc124'; newPassword = 'abc123' } | ConvertTo-Json)

# ---------- 2. 用户管理 ----------
Invoke-Api -Name '用户分页查询' -Method 'GET' -Url "$base/api/users?pageNum=1&pageSize=5" -Hdrs @{ Authorization = $adminToken }

$uBody = @{ email = "t_report_u1_$ts@test.com"; password = 'abc123'; nickname = '新增测试用户' } | ConvertTo-Json
$uResp = Invoke-Api -Name '新增用户' -Method 'POST' -Url "$base/api/users" -Hdrs @{ Authorization = $adminToken } -Body $uBody
$uid = (($uResp | ConvertFrom-Json).data.id)

Invoke-Api -Name '用户详情' -Method 'GET' -Url "$base/api/users/$uid" -Hdrs @{ Authorization = $adminToken }
Invoke-Api -Name '修改用户' -Method 'PUT' -Url "$base/api/users/$uid" -Hdrs @{ Authorization = $adminToken } -Body (@{ email = "t_report_u1_$ts@test.com"; nickname = '改后昵称'; status = 0 } | ConvertTo-Json)
Invoke-Api -Name '给用户分配角色' -Method 'PUT' -Url "$base/api/users/$uid/roles" -Hdrs @{ Authorization = $adminToken } -Body (@{ roleIds = @(1) } | ConvertTo-Json)
Invoke-Api -Name '删除用户' -Method 'DELETE' -Url "$base/api/users/$uid" -Hdrs @{ Authorization = $adminToken }

# ---------- 3. 角色管理 ----------
Invoke-Api -Name '角色分页查询' -Method 'GET' -Url "$base/api/roles?pageNum=1&pageSize=5" -Hdrs @{ Authorization = $adminToken }
Invoke-Api -Name '角色列表(下拉)' -Method 'GET' -Url "$base/api/roles/all" -Hdrs @{ Authorization = $adminToken }
Invoke-Api -Name '角色详情(ADMIN)' -Method 'GET' -Url "$base/api/roles/1" -Hdrs @{ Authorization = $adminToken }

$roleCode = "TEST_ROLE_$ts"
$rBody = @{ code = $roleCode; name = '测试角色'; description = '报告测试用'; status = 0 } | ConvertTo-Json
$rResp = Invoke-Api -Name '新增角色' -Method 'POST' -Url "$base/api/roles" -Hdrs @{ Authorization = $adminToken } -Body $rBody
$rid = (($rResp | ConvertFrom-Json).data.id)

Invoke-Api -Name '修改角色' -Method 'PUT' -Url "$base/api/roles/$rid" -Hdrs @{ Authorization = $adminToken } -Body (@{ code = $roleCode; name = '测试角色改'; description = '改'; status = 0 } | ConvertTo-Json)
Invoke-Api -Name '给角色分配权限' -Method 'PUT' -Url "$base/api/roles/$rid/permissions" -Hdrs @{ Authorization = $adminToken } -Body (@{ permissionIds = @(7, 8) } | ConvertTo-Json)
Invoke-Api -Name '删除角色' -Method 'DELETE' -Url "$base/api/roles/$rid" -Hdrs @{ Authorization = $adminToken }

# ---------- 4. 权限管理 ----------
Invoke-Api -Name '权限分页查询' -Method 'GET' -Url "$base/api/permissions?pageNum=1&pageSize=5" -Hdrs @{ Authorization = $adminToken }
Invoke-Api -Name '权限列表' -Method 'GET' -Url "$base/api/permissions/all" -Hdrs @{ Authorization = $adminToken }
Invoke-Api -Name '权限详情' -Method 'GET' -Url "$base/api/permissions/1" -Hdrs @{ Authorization = $adminToken }

# ---------- 5. 菜单管理 ----------
Invoke-Api -Name '菜单树(管理端全量)' -Method 'GET' -Url "$base/api/menus/tree" -Hdrs @{ Authorization = $adminToken }
Invoke-Api -Name '我的菜单(按权限过滤)' -Method 'GET' -Url "$base/api/menus/current" -Hdrs @{ Authorization = $userToken }

$menuCode = "test-menu-$ts"
$mBody = @{ code = $menuCode; name = '测试菜单'; type = 'MENU'; parentId = 0; path = '/test'; component = 'test/index'; icon = 'test'; visible = 1; sort = 99 } | ConvertTo-Json
$mResp = Invoke-Api -Name '新增菜单' -Method 'POST' -Url "$base/api/menus" -Hdrs @{ Authorization = $adminToken } -Body $mBody
$mid = (($mResp | ConvertFrom-Json).data.id)

Invoke-Api -Name '修改菜单' -Method 'PUT' -Url "$base/api/menus/$mid" -Hdrs @{ Authorization = $adminToken } -Body (@{ code = $menuCode; name = '测试菜单改'; type = 'MENU'; parentId = 0; visible = 1; sort = 99 } | ConvertTo-Json)
Invoke-Api -Name '删除菜单' -Method 'DELETE' -Url "$base/api/menus/$mid" -Hdrs @{ Authorization = $adminToken }

# ---------- 6. 操作日志 ----------
$logResp = Invoke-Api -Name '日志分页查询' -Method 'GET' -Url "$base/api/logs?pageNum=1&pageSize=5" -Hdrs @{ Authorization = $adminToken }
$logId = (($logResp | ConvertFrom-Json).data.records[0].id)
if ($logId) {
    Invoke-Api -Name '删除日志单条' -Method 'DELETE' -Url "$base/api/logs/$logId" -Hdrs @{ Authorization = $adminToken }
}

# ---------- 7. Excel 导入导出（文件流，记录元信息） ----------
function Invoke-File {
    param([string]$Name, [string]$Method, [string]$Url, [hashtable]$Hdrs = @{}, [string]$OutFile)
    $status = ''
    $meta = ''
    try {
        # 用 HttpClient 下载，可同时拿到真实响应头与字节内容（Invoke-WebRequest -OutFile 在 PS7 返回 null）
        $client = [System.Net.Http.HttpClient]::new()
        $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::$Method, $Url)
        if ($Hdrs.ContainsKey('Authorization')) {
            $req.Headers.TryAddWithoutValidation('Authorization', $Hdrs['Authorization']) | Out-Null
        }
        $respMsg = $client.SendAsync($req).Result
        $status = [int]$respMsg.StatusCode
        $bytes = $respMsg.Content.ReadAsByteArrayAsync().Result
        [System.IO.File]::WriteAllBytes($OutFile, $bytes)
        $ct = $respMsg.Content.Headers.ContentType
        $cd = $respMsg.Content.Headers.ContentDisposition
        $meta = "Content-Type: $ct | Content-Disposition: $cd | 文件大小: $($bytes.Length) bytes"
        $client.Dispose()
    } catch {
        $status = 'ERROR'
        $meta = $_.Exception.Message
    }
    $script:results += [pscustomobject]@{
        name = $Name; method = $Method; url = $Url
        auth = if ($Hdrs.ContainsKey('Authorization')) { $Hdrs['Authorization'] } else { '' }
        body = ''; status = $status; response = $meta
    }
    return $OutFile
}

$tplFile = Join-Path $env:TEMP "template-$ts.xlsx"
Invoke-File -Name '导入模板下载' -Method 'GET' -Url "$base/api/users/import/template" -Hdrs @{ Authorization = $adminToken } -OutFile $tplFile

$expFile = Join-Path $env:TEMP "export-$ts.xlsx"
Invoke-File -Name '用户导出' -Method 'GET' -Url "$base/api/users/export" -Hdrs @{ Authorization = $adminToken } -OutFile $expFile

# 导入：非法文件（伪造 zip 头，应解析失败返回校验错误）
$emptyFile = Join-Path $env:TEMP "invalid-$ts.xlsx"
[System.IO.File]::WriteAllBytes($emptyFile, [byte[]](0x50, 0x4B, 0x03, 0x04))
try {
    $impInvalid = Invoke-WebRequest -Uri "$base/api/users/import" -Method 'POST' -Headers @{ Authorization = $adminToken } -Form @{ file = Get-Item $emptyFile } -UseBasicParsing -TimeoutSec 30 -SkipHttpErrorCheck
    $script:results += [pscustomobject]@{
        name = '批量导入(非法文件)'; method = 'POST'; url = "$base/api/users/import"
        auth = 'Bearer <admin>'; body = 'multipart: file=invalid-xxx.xlsx（非法内容）'
        status = [int]$impInvalid.StatusCode; response = $impInvalid.Content
    }
} catch {
    $script:results += [pscustomobject]@{
        name = '批量导入(非法文件)'; method = 'POST'; url = "$base/api/users/import"
        auth = 'Bearer <admin>'; body = 'multipart: file=invalid-xxx.xlsx'; status = 'ERROR'; response = $_.Exception.Message
    }
}

# 导入：用导出文件回导（其中邮箱均已存在 -> 应整批校验拒绝并返回错误明细）
try {
    $impResp = Invoke-WebRequest -Uri "$base/api/users/import" -Method 'POST' -Headers @{ Authorization = $adminToken } -Form @{ file = Get-Item $expFile } -UseBasicParsing -TimeoutSec 30 -SkipHttpErrorCheck
    $script:results += [pscustomobject]@{
        name = '批量导入(导出文件回导)'; method = 'POST'; url = "$base/api/users/import"
        auth = 'Bearer <admin>'; body = 'multipart: file=export-xxx.xlsx（含已存在邮箱）'
        status = [int]$impResp.StatusCode; response = $impResp.Content
    }
} catch {
    $script:results += [pscustomobject]@{
        name = '批量导入(导出文件回导)'; method = 'POST'; url = "$base/api/users/import"
        auth = 'Bearer <admin>'; body = 'multipart: file=export-xxx.xlsx'; status = 'ERROR'; response = $_.Exception.Message
    }
}

# ---------- 8. 权限/认证边界 ----------
Invoke-Api -Name '未登录访问受保护接口' -Method 'GET' -Url "$base/api/users?pageNum=1&pageSize=5" -Hdrs @{}
Invoke-Api -Name '无权限访问(普通用户请求user:page)' -Method 'GET' -Url "$base/api/users?pageNum=1&pageSize=5" -Hdrs @{ Authorization = $userToken }

# ---------- 9. 清理测试资源 ----------
# 按邮箱查询注册测试用户 id 后删除
$regQuery = Invoke-Api -Name '清理：查询注册测试用户' -Method 'GET' -Url "$base/api/users?email=$regEmail&pageNum=1&pageSize=1" -Hdrs @{ Authorization = $adminToken }
try {
    $regUserId = (($regQuery | ConvertFrom-Json).data.records[0].id)
    if ($regUserId) {
        Invoke-Api -Name '清理：删除注册测试用户' -Method 'DELETE' -Url "$base/api/users/$regUserId" -Hdrs @{ Authorization = $adminToken }
    }
} catch { }
Invoke-Api -Name '清理：清空操作日志' -Method 'DELETE' -Url "$base/api/logs" -Hdrs @{ Authorization = $adminToken }

# ---------- 输出 ----------
$results | ConvertTo-Json -Depth 12 | Set-Content -Path $outFile -Encoding UTF8
Write-Host "共执行 $($results.Count) 个接口调用，结果已写入 $outFile"
