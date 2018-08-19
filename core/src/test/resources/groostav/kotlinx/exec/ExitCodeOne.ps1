Write-Verbose "running ExitCodeOne.ps1"

# annoyingly, powershell doesnt give direct access to std-error,
# the require us to use this and they auto-format a stack-trace on it.
# they prepend a bunch of text and format it for 80 columns,
# so to hedge our bets that this string shows up on a single line,
# I'm prepending a newline char
Write-Error -Message "`nScript is exiting with code 1"

exit 1