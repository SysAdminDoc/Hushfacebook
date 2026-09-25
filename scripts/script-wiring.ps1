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
    where the script can reach it: not in a function nothing reachable calls, not in a script
    block nothing runs, not in an if arm a constant condition rules out, not in a while or for body
    a constant false condition skips, and not after a statement that ends its block. That is an
    exit, return, throw, break or continue, a call to a function that never returns, an if that
    ends on every arm that can run, or a loop a constant true condition keeps going with no break
    to leave it. The checks built on that match the call and its arguments, not a line that names
    them.
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

function Get-ConstantValue {
    <#
    .SYNOPSIS
        What an expression comes to when the parser settles it on its own, as .Value on what comes
        back so that a $false or $null result isn't taken for not knowing, else $null. That is
        $true, $false, $null, a number or a string, in parentheses or negated, or two of those
        compared, as in (0 -eq 1).
    #>
    param($Node)

    while ($true) {
        if ($Node -is [System.Management.Automation.Language.PipelineAst] -and $Node.PipelineElements.Count -eq 1 -and
                $Node.PipelineElements[0] -is [System.Management.Automation.Language.CommandExpressionAst]) {
            $Node = $Node.PipelineElements[0].Expression
        } elseif ($Node -is [System.Management.Automation.Language.ParenExpressionAst]) {
            $Node = $Node.Pipeline
        } else {
            break
        }
    }
    if ($Node -is [System.Management.Automation.Language.VariableExpressionAst]) {
        # Ordinal: a culture-aware compare takes a name with an invisible character in it for these.
        $constants = @{ 'true' = $true; 'false' = $false; 'null' = $null }
        foreach ($name in 'true', 'false', 'null') {
            if ([string]::Equals($Node.VariablePath.UserPath, $name, [System.StringComparison]::OrdinalIgnoreCase)) {
                return [pscustomobject]@{ Value = $constants[$name] }
            }
        }
        return $null
    }
    if ($Node -is [System.Management.Automation.Language.ConstantExpressionAst]) {
        return [pscustomobject]@{ Value = $Node.Value }
    }
    if ($Node -is [System.Management.Automation.Language.UnaryExpressionAst] -and
            ($Node.TokenKind -eq 'Not' -or $Node.TokenKind -eq 'Exclaim')) {
        $inner = Get-ConstantValue $Node.Child
        if ($null -ne $inner) { return [pscustomobject]@{ Value = -not $inner.Value } }
        return $null
    }
    if ($Node -is [System.Management.Automation.Language.BinaryExpressionAst]) {
        $left = Get-ConstantValue $Node.Left
        $right = Get-ConstantValue $Node.Right
        if ($null -eq $left -or $null -eq $right) { return $null }
        # Compared here the way the script would compare them when it runs.
        $a = $left.Value
        $b = $right.Value
        switch ($Node.Operator.ToString()) {
            'Ieq' { return [pscustomobject]@{ Value = $a -eq $b } }
            'Ine' { return [pscustomobject]@{ Value = $a -ne $b } }
            'Igt' { return [pscustomobject]@{ Value = $a -gt $b } }
            'Ige' { return [pscustomobject]@{ Value = $a -ge $b } }
            'Ilt' { return [pscustomobject]@{ Value = $a -lt $b } }
            'Ile' { return [pscustomobject]@{ Value = $a -le $b } }
            'Ceq' { return [pscustomobject]@{ Value = $a -ceq $b } }
            'Cne' { return [pscustomobject]@{ Value = $a -cne $b } }
            'Cgt' { return [pscustomobject]@{ Value = $a -cgt $b } }
            'Cge' { return [pscustomobject]@{ Value = $a -cge $b } }
            'Clt' { return [pscustomobject]@{ Value = $a -clt $b } }
            'Cle' { return [pscustomobject]@{ Value = $a -cle $b } }
        }
    }
    return $null
}

function Get-ConstantTruth {
    <#
    .SYNOPSIS
        $true or $false for a condition the parser settles on its own (Get-ConstantValue), else
        $null.
    #>
    param($Condition)

    $constant = Get-ConstantValue $Condition
    if ($null -eq $constant) { return $null }
    return [bool]$constant.Value
}

function Test-RunsScriptBlock {
    <#
    .SYNOPSIS
        Whether a script block runs where it's written: as the command itself, which only & or .
        can make it, or as what ForEach-Object or Where-Object runs for each item. Stored, returned
        or handed to any other command it's a value, and nothing here is known to run it.
    #>
    param([System.Management.Automation.Language.ScriptBlockExpressionAst]$Expression)

    $command = $Expression.Parent
    if ($command -is [System.Management.Automation.Language.CommandParameterAst]) { $command = $command.Parent }
    if ($command -isnot [System.Management.Automation.Language.CommandAst]) { return $false }
    if ([object]::ReferenceEquals($command.CommandElements[0], $Expression)) { return $true }
    $name = $command.GetCommandName()
    foreach ($runner in 'ForEach-Object', 'Where-Object', '%', '?', 'foreach', 'where') {
        if ([string]::Equals($name, $runner, [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}

function Test-StatementEnds {
    <#
    .SYNOPSIS
        Whether nothing after this statement in its block can run: an exit, return, throw, break
        or continue; a call to a function that never returns, on its own, in a pipeline or
        assigned; an if one of whose arms always runs, where every arm that can run ends; or a
        while or for loop that a constant true condition keeps going, with no break in it.
    #>
    param($Statement, [System.Collections.Generic.HashSet[string]]$Ending)

    if ($Statement -is [System.Management.Automation.Language.ExitStatementAst] -or
            $Statement -is [System.Management.Automation.Language.ReturnStatementAst] -or
            $Statement -is [System.Management.Automation.Language.ThrowStatementAst] -or
            $Statement -is [System.Management.Automation.Language.BreakStatementAst] -or
            $Statement -is [System.Management.Automation.Language.ContinueStatementAst]) {
        return $true
    }
    $pipeline = $Statement
    while ($pipeline -is [System.Management.Automation.Language.AssignmentStatementAst]) { $pipeline = $pipeline.Right }
    if ($pipeline -is [System.Management.Automation.Language.PipelineAst]) {
        foreach ($element in $pipeline.PipelineElements) {
            if ($element -is [System.Management.Automation.Language.CommandAst] -and
                $Ending.Contains([string]$element.GetCommandName())) { return $true }
        }
        return $false
    }
    if ($Statement -is [System.Management.Automation.Language.IfStatementAst]) {
        foreach ($clause in $Statement.Clauses) {
            $truth = Get-ConstantTruth $clause.Item1
            if ($truth -eq $false) { continue }
            if (-not (Test-BlockEnds $clause.Item2 $Ending)) { return $false }
            if ($truth -eq $true) { return $true }
        }
        return $null -ne $Statement.ElseClause -and (Test-BlockEnds $Statement.ElseClause $Ending)
    }
    if ($Statement -is [System.Management.Automation.Language.WhileStatementAst] -or
            $Statement -is [System.Management.Automation.Language.ForStatementAst]) {
        if ($null -ne $Statement.Condition -and (Get-ConstantTruth $Statement.Condition) -ne $true) { return $false }
        return $null -eq $Statement.Body.Find({ param($node)
            $node -is [System.Management.Automation.Language.BreakStatementAst] }, $true)
    }
    return $false
}

function Test-BlockEnds {
    <#
    .SYNOPSIS
        Whether one of a block's statements ends it (Test-StatementEnds).
    #>
    param($Block, [System.Collections.Generic.HashSet[string]]$Ending)

    foreach ($statement in $Block.Statements) {
        if (Test-StatementEnds $statement $Ending) { return $true }
    }
    return $false
}

function Get-EndingFunctions {
    <#
    .SYNOPSIS
        The functions a script defines that never hand control back: no return anywhere in them,
        and a statement of their body that ends it, such as an exit, a throw or a call to another
        of these. A call to one ends the block it's in the way an exit would.
    #>
    param([System.Management.Automation.Language.Ast]$Ast)

    $functions = @($Ast.FindAll({ param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true))
    $ending = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    do {
        $grew = $false
        foreach ($function in $functions) {
            if ($ending.Contains($function.Name) -or $null -eq $function.Body.EndBlock -or
                $null -ne $function.Body.Find({ param($node)
                    $node -is [System.Management.Automation.Language.ReturnStatementAst] }, $true)) { continue }
            if (Test-BlockEnds $function.Body.EndBlock $ending) {
                [void]$ending.Add($function.Name)
                $grew = $true
            }
        }
    } while ($grew)
    return , $ending
}

function Test-AstReachable {
    <#
    .SYNOPSIS
        Whether the script can reach this node, given the functions it's known to call and the
        ones that never return (Get-EndingFunctions).
    #>
    param($Node, [System.Collections.Generic.HashSet[string]]$Called,
        [System.Collections.Generic.HashSet[string]]$Ending)

    $child = $Node
    $parent = $Node.Parent
    while ($null -ne $parent) {
        if ($parent -is [System.Management.Automation.Language.FunctionDefinitionAst]) {
            if (-not $Called.Contains($parent.Name)) { return $false }
        } elseif ($parent -is [System.Management.Automation.Language.ScriptBlockExpressionAst]) {
            if (-not (Test-RunsScriptBlock $parent)) { return $false }
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
                if (Test-StatementEnds $statement $Ending) { return $false }
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
    param([System.Management.Automation.Language.Ast]$Ast, [System.Collections.Generic.HashSet[string]]$Ending)

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
            if (Test-AstReachable $command $called $Ending) {
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

    $ending = Get-EndingFunctions $Ast
    $called = Get-CalledFunctions $Ast $ending
    return @($Ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] }, $true) |
        Where-Object { Test-AstReachable $_ $called $ending })
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
        patched APK, each into a variable of its own that nothing else writes, and compares
        exactly those two, with the helpers' functions rather than ones the script defines.
    #>
    param([string]$Path)

    $ast = Get-ScriptAst $Path
    # A function of either name defined in the script takes the helper's place, whatever it does.
    foreach ($function in @($ast.FindAll({ param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true))) {
        $name = ($function.Name -split ':')[-1]
        foreach ($helper in 'Compare-VerifierTallies', 'Invoke-AndroidVerifierTally') {
            if ([string]::Equals($name, $helper, [System.StringComparison]::OrdinalIgnoreCase)) { return $false }
        }
    }
    $live = @(Get-LiveCommands $ast)
    $tallies = @{}
    foreach ($tally in @($live | Where-Object { $_.GetCommandName() -eq 'Invoke-AndroidVerifierTally' })) {
        $local = Get-CommandArgument $tally 'Local'
        $variable = Get-AssignedVariable $tally
        if ($local -and $variable) { $tallies[$local.Extent.Text] = $variable }
    }
    $clean = $tallies['$cleanBase']
    $patched = $tallies['$PatchedApk']
    if (-not $clean -or -not $patched) { return $false }
    # One assignment to each variable anywhere in the script, with a scope prefix or without: its
    # tally. A copy of the other tally written over it, or both tallies taken into one variable,
    # would leave every device run comparing the patched build with itself.
    foreach ($variable in $clean, $patched) {
        $bare = ($variable -split ':')[-1]
        $writes = @($ast.FindAll({ param($node)
            $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $null -ne $node.Left.Find({ param($target)
                $target -is [System.Management.Automation.Language.VariableExpressionAst] -and
                [string]::Equals(($target.VariablePath.UserPath -split ':')[-1], $bare,
                    [System.StringComparison]::OrdinalIgnoreCase) }, $true) }, $true))
        if ($writes.Count -ne 1) { return $false }
    }
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
    $ending = Get-EndingFunctions $ast
    $called = Get-CalledFunctions $ast $ending
    $written = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.ArrayLiteralAst] }, $true) |
        Where-Object { Test-AstReachable $_ $called $ending } | ForEach-Object {
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
            Where-Object { Test-AstReachable $_ $called $ending })) {
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
    $ending = Get-EndingFunctions $ast
    $called = Get-CalledFunctions $ast $ending
    foreach ($assignment in $ast.FindAll({ param($node)
            $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $node.Operator -eq [System.Management.Automation.Language.TokenKind]::PlusEquals -and
            $node.Left -is [System.Management.Automation.Language.VariableExpressionAst] -and
            $node.Left.VariablePath.UserPath -eq 'suites' }, $true)) {
        $first = $assignment.Right.Find({ param($node)
            $node -is [System.Management.Automation.Language.StringConstantExpressionAst] }, $true)
        if ($null -ne $first -and $first.Value -eq $Suite -and (Test-AstReachable $assignment $called $ending)) { return $true }
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
