<#
.SYNOPSIS
    What a PowerShell script runs, read through its parser, for the suites that check how the
    injected-register verifier and the push gate are wired together.

.DESCRIPTION
    Dot-source this in a suite:

        . (Join-Path $PSScriptRoot 'script-wiring.ps1')

    Help text and comments never parse into commands, but that alone still let a wiring check pass
    on a Write-Host line naming the files, a call inside a function nothing calls, a dot-source
    inside `if ($false) { }`, and a suite line inside a block comment. A command counts here only
    where the script can reach it: not in a function nothing reachable calls, not in an if arm a
    constant condition rules out, not in a while or for body a constant false condition skips, and
    not after an unconditional exit, return, throw, break or continue in its own block. The checks
    built on that match the call and its arguments, not a line that names them.
#>

function Get-ScriptAst {
    <#
    .SYNOPSIS
        A script's syntax tree, or a throw when it doesn't parse.
    .DESCRIPTION
        Parsed from the file's text read as UTF-8, so both editions see the same tree. Windows
        PowerShell's ParseFile reads a file without a byte order mark as ANSI.
    #>
    param([string]$Path)

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput(
        [System.IO.File]::ReadAllText($Path), $Path, [ref]$tokens, [ref]$errors)
    if ($errors.Count -ne 0) { throw "$Path does not parse: $($errors[0].Message)" }
    return $ast
}

function Get-ConstantTruth {
    <#
    .SYNOPSIS
        $true or $false for a condition the parser settles on its own ($true, $false, $null, a
        number or a string, in parentheses or negated), else $null.
    #>
    param($Condition)

    $node = $Condition
    while ($true) {
        if ($node -is [System.Management.Automation.Language.PipelineAst] -and $node.PipelineElements.Count -eq 1 -and
                $node.PipelineElements[0] -is [System.Management.Automation.Language.CommandExpressionAst]) {
            $node = $node.PipelineElements[0].Expression
        } elseif ($node -is [System.Management.Automation.Language.ParenExpressionAst]) {
            $node = $node.Pipeline
        } else {
            break
        }
    }
    if ($node -is [System.Management.Automation.Language.VariableExpressionAst]) {
        switch ($node.VariablePath.UserPath) {
            'true' { return $true }
            'false' { return $false }
            'null' { return $false }
        }
        return $null
    }
    if ($node -is [System.Management.Automation.Language.ConstantExpressionAst]) { return [bool]$node.Value }
    if ($node -is [System.Management.Automation.Language.UnaryExpressionAst] -and
            ($node.TokenKind -eq 'Not' -or $node.TokenKind -eq 'Exclaim')) {
        $inner = Get-ConstantTruth $node.Child
        if ($null -ne $inner) { return -not $inner }
    }
    return $null
}

function Test-AstReachable {
    <#
    .SYNOPSIS
        Whether the script can reach this node, given the functions it's known to call.
    #>
    param($Node, [System.Collections.Generic.HashSet[string]]$Called)

    $child = $Node
    $parent = $Node.Parent
    while ($null -ne $parent) {
        if ($parent -is [System.Management.Automation.Language.FunctionDefinitionAst]) {
            if (-not $Called.Contains($parent.Name)) { return $false }
        } elseif ($parent -is [System.Management.Automation.Language.IfStatementAst]) {
            # An arm is dead when its own condition is constant false or an earlier condition is
            # constant true, and the else is dead after any constant true.
            $taken = $false
            foreach ($clause in $parent.Clauses) {
                $inCondition = [object]::ReferenceEquals($clause.Item1, $child)
                $inBody = [object]::ReferenceEquals($clause.Item2, $child)
                if ($inCondition -or $inBody) {
                    if ($taken -or ($inBody -and (Get-ConstantTruth $clause.Item1) -eq $false)) { return $false }
                    break
                }
                if ((Get-ConstantTruth $clause.Item1) -eq $true) { $taken = $true }
            }
            if ($taken -and [object]::ReferenceEquals($parent.ElseClause, $child)) { return $false }
        } elseif ($parent -is [System.Management.Automation.Language.WhileStatementAst] -or
                $parent -is [System.Management.Automation.Language.ForStatementAst]) {
            if ([object]::ReferenceEquals($parent.Body, $child) -and
                    (Get-ConstantTruth $parent.Condition) -eq $false) { return $false }
        } elseif ($parent -is [System.Management.Automation.Language.StatementBlockAst] -or
                $parent -is [System.Management.Automation.Language.NamedBlockAst]) {
            foreach ($statement in $parent.Statements) {
                if ([object]::ReferenceEquals($statement, $child)) { break }
                if ($statement -is [System.Management.Automation.Language.ExitStatementAst] -or
                        $statement -is [System.Management.Automation.Language.ReturnStatementAst] -or
                        $statement -is [System.Management.Automation.Language.ThrowStatementAst] -or
                        $statement -is [System.Management.Automation.Language.BreakStatementAst] -or
                        $statement -is [System.Management.Automation.Language.ContinueStatementAst]) {
                    return $false
                }
            }
        }
        $child = $parent
        $parent = $parent.Parent
    }
    return $true
}

function Get-CalledFunctions {
    <#
    .SYNOPSIS
        The functions a script defines and reaches a call to: from its code outside every
        function first, then from inside each function found called, until nothing new turns up.
    #>
    param([System.Management.Automation.Language.Ast]$Ast)

    $defined = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($function in $Ast.FindAll({ param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true)) {
        [void]$defined.Add($function.Name)
    }
    $commands = @($Ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] }, $true))
    $called = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    do {
        $grew = $false
        foreach ($command in $commands) {
            $name = $command.GetCommandName()
            if (-not $name -or -not $defined.Contains($name) -or $called.Contains($name)) { continue }
            if (Test-AstReachable $command $called) {
                [void]$called.Add($name)
                $grew = $true
            }
        }
    } while ($grew)
    return , $called
}

function Get-LiveCommands {
    <#
    .SYNOPSIS
        Every command in a script that the script can reach.
    #>
    param([System.Management.Automation.Language.Ast]$Ast)

    $called = Get-CalledFunctions $Ast
    return @($Ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] }, $true) |
        Where-Object { Test-AstReachable $_ $called })
}

function Test-NamesFile {
    <#
    .SYNOPSIS
        Whether part of a command names this file in a string, as (Join-Path $PSScriptRoot 'x.ps1') does.
    #>
    param($Element, [string]$File)

    $null -ne $Element.Find({ param($node)
        $node -is [System.Management.Automation.Language.StringConstantExpressionAst] -and
        [System.IO.Path]::GetFileName($node.Value) -eq $File }, $true)
}

function Get-CommandArgument {
    <#
    .SYNOPSIS
        What a command passes to -Name, written -Name value or -Name:value, or $null.
    #>
    param([System.Management.Automation.Language.CommandAst]$Command, [string]$Name)

    $elements = $Command.CommandElements
    for ($k = 1; $k -lt $elements.Count; $k++) {
        $element = $elements[$k]
        if ($element -isnot [System.Management.Automation.Language.CommandParameterAst] -or
            $element.ParameterName -ne $Name) { continue }
        if ($null -ne $element.Argument) { return $element.Argument }
        if ($k + 1 -lt $elements.Count -and
            $elements[$k + 1] -isnot [System.Management.Automation.Language.CommandParameterAst]) {
            return $elements[$k + 1]
        }
        return $null
    }
    return $null
}

function Get-AssignedVariable {
    <#
    .SYNOPSIS
        The variable a command's output goes to on its own, as in $x = Command, or $null.
    #>
    param([System.Management.Automation.Language.CommandAst]$Command)

    $pipeline = $Command.Parent
    if ($pipeline -isnot [System.Management.Automation.Language.PipelineAst] -or
        $pipeline.PipelineElements.Count -ne 1) { return $null }
    $assignment = $pipeline.Parent
    if ($assignment -isnot [System.Management.Automation.Language.AssignmentStatementAst] -or
        $assignment.Operator -ne [System.Management.Automation.Language.TokenKind]::Equals -or
        $assignment.Left -isnot [System.Management.Automation.Language.VariableExpressionAst]) { return $null }
    return $assignment.Left.VariablePath.UserPath
}

function Test-DotSourcesFile {
    <#
    .SYNOPSIS
        Whether a script dot-sources this file where it runs, outside every function, so what the
        file defines is there for the rest of the script.
    #>
    param([string]$Path, [string]$File)

    foreach ($command in @(Get-LiveCommands (Get-ScriptAst $Path))) {
        if ($command.InvocationOperator -ne [System.Management.Automation.Language.TokenKind]::Dot -or
            -not (Test-NamesFile $command.CommandElements[0] $File)) { continue }
        $insideFunction = $false
        for ($parent = $command.Parent; $null -ne $parent; $parent = $parent.Parent) {
            if ($parent -is [System.Management.Automation.Language.FunctionDefinitionAst]) { $insideFunction = $true }
        }
        if (-not $insideFunction) { return $true }
    }
    return $false
}

function Test-RunsDexDiffWithContracts {
    <#
    .SYNOPSIS
        Whether verify-injected-registers.ps1 runs DexDiff.java on the patched APK and holds it
        to the mutation contracts: a reachable & $Java call whose DexDiff arguments are the clean
        APK, $PatchedApk, the report, the removal allowlist and injected-mutation-contracts.txt.
    #>
    param([string]$Path)

    foreach ($call in @(Get-LiveCommands (Get-ScriptAst $Path))) {
        if ($call.InvocationOperator -ne [System.Management.Automation.Language.TokenKind]::Ampersand -or
            $call.CommandElements[0].Extent.Text -ne '$Java') { continue }
        $elements = $call.CommandElements
        for ($k = 1; $k + 5 -lt $elements.Count; $k++) {
            if ((Test-NamesFile $elements[$k] 'DexDiff.java') -and $elements[$k + 2].Extent.Text -eq '$PatchedApk' -and
                (Test-NamesFile $elements[$k + 5] 'injected-mutation-contracts.txt')) { return $true }
        }
    }
    return $false
}

function Test-TalliesBothSides {
    <#
    .SYNOPSIS
        Whether verify-injected-registers.ps1 takes a device tally of the clean APK and one of the
        patched APK, each into a variable of its own, and compares exactly those two.
    #>
    param([string]$Path)

    $live = @(Get-LiveCommands (Get-ScriptAst $Path))
    $tallies = @{}
    foreach ($tally in @($live | Where-Object { $_.GetCommandName() -eq 'Invoke-AndroidVerifierTally' })) {
        $local = Get-CommandArgument $tally 'Local'
        $variable = Get-AssignedVariable $tally
        if ($local -and $variable) { $tallies[$local.Extent.Text] = $variable }
    }
    $clean = $tallies['$cleanBase']
    $patched = $tallies['$PatchedApk']
    if (-not $clean -or -not $patched -or $clean -eq $patched) { return $false }
    foreach ($compare in @($live | Where-Object { $_.GetCommandName() -eq 'Compare-VerifierTallies' })) {
        $cleanArgument = Get-CommandArgument $compare 'Clean'
        $patchedArgument = Get-CommandArgument $compare 'Patched'
        if ($cleanArgument -and $patchedArgument -and $cleanArgument.Extent.Text -eq "`$$clean" -and
            $patchedArgument.Extent.Text -eq "`$$patched") { return $true }
    }
    return $false
}

function Test-VerifiesWhatItPatched {
    <#
    .SYNOPSIS
        Whether verify-all-patches.ps1 runs verify-injected-registers.ps1 on the APK the CLI
        wrote: a reachable & call to that script whose -PatchedApk is the variable that follows
        '-o' in a reachable argument list.
    #>
    param([string]$Path)

    $ast = Get-ScriptAst $Path
    $called = Get-CalledFunctions $ast
    $written = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.ArrayLiteralAst] }, $true) |
        Where-Object { Test-AstReachable $_ $called } | ForEach-Object {
            $elements = $_.Elements
            for ($k = 0; $k + 1 -lt $elements.Count; $k++) {
                if ($elements[$k] -is [System.Management.Automation.Language.StringConstantExpressionAst] -and
                    $elements[$k].Value -eq '-o' -and
                    $elements[$k + 1] -is [System.Management.Automation.Language.VariableExpressionAst]) {
                    $elements[$k + 1].Extent.Text
                }
            }
        })
    foreach ($call in @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] }, $true) |
            Where-Object { Test-AstReachable $_ $called })) {
        if ($call.InvocationOperator -ne [System.Management.Automation.Language.TokenKind]::Ampersand -or
            -not (Test-NamesFile $call.CommandElements[0] 'verify-injected-registers.ps1')) { continue }
        $patched = Get-CommandArgument $call 'PatchedApk'
        if ($patched -and $written -contains $patched.Extent.Text) { return $true }
    }
    return $false
}

function Test-PushGateRunsSuite {
    <#
    .SYNOPSIS
        Whether pre-push.ps1 adds this suite to its run: a reachable $suites += assignment whose
        first string is the suite's path. A suite line in a comment or a dead branch doesn't count.
    #>
    param([string]$Path, [string]$Suite)

    $ast = Get-ScriptAst $Path
    $called = Get-CalledFunctions $ast
    foreach ($assignment in $ast.FindAll({ param($node)
            $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $node.Operator -eq [System.Management.Automation.Language.TokenKind]::PlusEquals -and
            $node.Left -is [System.Management.Automation.Language.VariableExpressionAst] -and
            $node.Left.VariablePath.UserPath -eq 'suites' }, $true)) {
        $first = $assignment.Right.Find({ param($node)
            $node -is [System.Management.Automation.Language.StringConstantExpressionAst] }, $true)
        if ($null -ne $first -and $first.Value -eq $Suite -and (Test-AstReachable $assignment $called)) { return $true }
    }
    return $false
}

function Edit-ScriptNode {
    <#
    .SYNOPSIS
        A script's text with the first node -Where picks replaced by what -Replace makes of that
        node's text.
    .DESCRIPTION
        The suites take the wiring out of a copy this way, in the shapes that leave its text in
        place, and check that the checks above notice. It throws when nothing matches, so a case
        the script has outgrown can't pass by editing nothing.
    #>
    param([string]$Text, [scriptblock]$Where, [scriptblock]$Replace)

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text, [ref]$tokens, [ref]$errors)
    $node = $ast.Find($Where, $true)
    if ($null -eq $node) { throw 'No node matched the edit.' }
    return $Text.Substring(0, $node.Extent.StartOffset) + (& $Replace $node.Extent.Text) +
        $Text.Substring($node.Extent.EndOffset)
}
