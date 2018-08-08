Param(
    [int]$ExitCode = 0,
    [string]$Message = $null,
    [switch]$ThrowError = $false
)

if(-not $Message -eq $null){
    Write-Output $Message
}

if($ThrowError){
    throw "this is an important message!"
}

Write-Output "env:GROOSTAV_ENV_VALUE is '$($env:GROOSTAV_ENV_VALUE)'"

exit $ExitCode