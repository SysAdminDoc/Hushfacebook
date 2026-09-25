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
    where the script can reach it: not in a function nothing reachable calls or one the script
    defines twice, not in a script block nothing runs, not in an if arm a constant condition rules
    out, not in a while or for body a constant false condition skips, not in a switch clause whose
    constant label can't match a constant subject, not in a foreach over @() or $null, not in the
    catch of a try that can't throw, not on the right of an -and or -or whose left side settles
    it, and not after a statement that ends its block. That is an exit, return, throw, break or
    continue, a call to a function that never returns, a script block run with & or . that ends by
    anything but a return, an if that ends on every arm that can run, a try whose finally ends or
    whose body ends where no catch can carry on past it, a switch where a clause that certainly
    runs ends or every clause that can run ends, default included, a do loop whose body ends, or a
    loop a constant true condition keeps going with no break to leave it. The checks built on that
    match the call and its arguments, not a line that names them.
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
        $true, $false, $null, a number or a string, in parentheses or negated, two of those
        compared, as in (0 -eq 1), or joined with -and, -or or -xor. One constant settles -and
        when it's false and -or when it's true, whatever the other side is.
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
        $operator = $Node.Operator.ToString()
        if ($operator -eq 'And' -or $operator -eq 'Or') {
            $settles = $operator -eq 'Or'
            foreach ($side in $left, $right) {
                if ($null -ne $side -and [bool]$side.Value -eq $settles) { return [pscustomobject]@{ Value = $settles } }
            }
        }
        if ($null -eq $left -or $null -eq $right) { return $null }
        # Compared here the way the script would compare them when it runs.
        $a = $left.Value
        $b = $right.Value
        switch ($operator) {
            'And' { return [pscustomobject]@{ Value = $a -and $b } }
            'Or' { return [pscustomobject]@{ Value = $a -or $b } }
            'Xor' { return [pscustomobject]@{ Value = $a -xor $b } }
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
        or continue; a call to a function that never returns, or a script block run with & or .
        that leaves (Test-RunBlockEnds), on its own, in a pipeline or assigned; an if one of whose
        arms always runs, where every arm that can run ends; a try whose finally ends, or whose
        body ends where no catch can run or every catch ends too; a switch where a clause that
        certainly matches ends, or one with a default where every clause that can match ends; a do
        loop whose body ends; or a while or for loop that a constant true condition keeps going,
        with no break in it.
    .DESCRIPTION
        -Escapes names the jumps that leave the block the statement sits in: all five at the top
        of a block. A break or continue in a switch clause or a do body only leaves that switch or
        loop, so a switch or do with one in it never counts as ending, and inside its clauses or
        body only an exit, return or throw leaves the block around it. A script block run with &
        or . is the other way round: a return only leaves the script block (Test-RunBlockEnds).
    #>
    param($Statement, [System.Collections.Generic.HashSet[string]]$Ending,
        [string[]]$Escapes = @('exit', 'return', 'throw', 'break', 'continue'))

    $jump = Get-JumpKind $Statement
    if ($jump) { return $Escapes -contains $jump }
    $pipeline = $Statement
    while ($pipeline -is [System.Management.Automation.Language.AssignmentStatementAst]) { $pipeline = $pipeline.Right }
    if ($pipeline -is [System.Management.Automation.Language.PipelineAst]) {
        for ($k = 0; $k -lt $pipeline.PipelineElements.Count; $k++) {
            $element = $pipeline.PipelineElements[$k]
            if ($element -isnot [System.Management.Automation.Language.CommandAst]) { continue }
            if ($Ending.Contains([string]$element.GetCommandName())) { return $true }
            if (Test-RunBlockEnds $element $Ending ($k -eq 0)) { return $true }
        }
        return $false
    }
    if ($Statement -is [System.Management.Automation.Language.IfStatementAst]) {
        foreach ($clause in $Statement.Clauses) {
            $truth = Get-ConstantTruth $clause.Item1
            if ($truth -eq $false) { continue }
            if (-not (Test-BlockEnds $clause.Item2 $Ending $Escapes)) { return $false }
            if ($truth -eq $true) { return $true }
        }
        return $null -ne $Statement.ElseClause -and (Test-BlockEnds $Statement.ElseClause $Ending $Escapes)
    }
    if ($Statement -is [System.Management.Automation.Language.TryStatementAst]) {
        # A finally that ends ends the try whatever came before it. Otherwise the body has to end,
        # and a catch that finishes carries on after the try, so every catch has to end as well,
        # unless the body leaves at its first statement with nothing that could throw, as a bare
        # exit does. No catch catches a jump.
        if ($null -ne $Statement.Finally -and (Test-BlockEnds $Statement.Finally $Ending $Escapes)) { return $true }
        if (-not (Test-BlockEnds $Statement.Body $Ending $Escapes)) { return $false }
        if (Test-PlainJump $Statement.Body.Statements[0]) { return $true }
        foreach ($catch in $Statement.CatchClauses) {
            if (-not (Test-BlockEnds $catch.Body $Ending $Escapes)) { return $false }
        }
        return $true
    }
    # What a switch clause or a loop body can use to leave the statement it's in.
    $inner = @($Escapes | Where-Object { $_ -ne 'break' -and $_ -ne 'continue' })
    if ($Statement -is [System.Management.Automation.Language.SwitchStatementAst]) {
        # A clause that certainly matches and ends ends the switch. Otherwise every clause that
        # can match has to end, and the default has to be there and end too. A literal @() runs
        # none of them.
        if ((Test-LeavesLoop $Statement) -or (Test-EmptyArray $Statement.Condition)) { return $false }
        $every = $true
        foreach ($clause in $Statement.Clauses) {
            $match = Test-SwitchClauseMatches $Statement $clause.Item1
            if ($match -eq $false) { continue }
            $ends = Test-BlockEnds $clause.Item2 $Ending $inner
            if ($ends -and $match -eq $true) { return $true }
            if (-not $ends) { $every = $false }
        }
        return $every -and $null -ne $Statement.Default -and (Test-BlockEnds $Statement.Default $Ending $inner)
    }
    if ($Statement -is [System.Management.Automation.Language.DoWhileStatementAst] -or
            $Statement -is [System.Management.Automation.Language.DoUntilStatementAst]) {
        # The body runs once before the condition is asked at all.
        if (Test-LeavesLoop $Statement.Body) { return $false }
        return Test-BlockEnds $Statement.Body $Ending $inner
    }
    if ($Statement -is [System.Management.Automation.Language.WhileStatementAst] -or
            $Statement -is [System.Management.Automation.Language.ForStatementAst]) {
        if ($null -ne $Statement.Condition -and (Get-ConstantTruth $Statement.Condition) -ne $true) { return $false }
        return $null -eq $Statement.Body.Find({ param($node)
            $node -is [System.Management.Automation.Language.BreakStatementAst] }, $true)
    }
    return $false
}

function Get-JumpKind {
    <#
    .SYNOPSIS
        'exit', 'return', 'throw', 'break' or 'continue' for a statement that is one, else $null.
    #>
    param($Statement)

    if ($Statement -is [System.Management.Automation.Language.ExitStatementAst]) { return 'exit' }
    if ($Statement -is [System.Management.Automation.Language.ReturnStatementAst]) { return 'return' }
    if ($Statement -is [System.Management.Automation.Language.ThrowStatementAst]) { return 'throw' }
    if ($Statement -is [System.Management.Automation.Language.BreakStatementAst]) { return 'break' }
    if ($Statement -is [System.Management.Automation.Language.ContinueStatementAst]) { return 'continue' }
    return $null
}

function Test-PlainJump {
    <#
    .SYNOPSIS
        Whether a statement is an exit, return, break or continue that runs nothing first that
        could throw: no value or label, or a constant one.
    #>
    param($Statement)

    if ($Statement -is [System.Management.Automation.Language.ExitStatementAst] -or
            $Statement -is [System.Management.Automation.Language.ReturnStatementAst]) {
        return $null -eq $Statement.Pipeline -or $null -ne (Get-ConstantValue $Statement.Pipeline)
    }
    if ($Statement -is [System.Management.Automation.Language.BreakStatementAst] -or
            $Statement -is [System.Management.Automation.Language.ContinueStatementAst]) {
        return $null -eq $Statement.Label -or $null -ne (Get-ConstantValue $Statement.Label)
    }
    return $false
}

function Test-RunBlockEnds {
    <#
    .SYNOPSIS
        Whether a command is a script block run with & or . that leaves the block the command
        sits in: its begin block, its end block, or its process block when it's first in the
        pipeline and so runs once, ends with an exit, throw, break or continue. A break or
        continue carries on out to the loop around the command. A return only leaves the named
        block it's in, so a named block with one of its own doesn't count.
    #>
    param([System.Management.Automation.Language.CommandAst]$Command,
        [System.Collections.Generic.HashSet[string]]$Ending, [bool]$First)

    $expression = $Command.CommandElements[0]
    if ($expression -isnot [System.Management.Automation.Language.ScriptBlockExpressionAst] -or
        ($Command.InvocationOperator -ne [System.Management.Automation.Language.TokenKind]::Ampersand -and
        $Command.InvocationOperator -ne [System.Management.Automation.Language.TokenKind]::Dot)) { return $false }
    $body = $expression.ScriptBlock
    $named = @($body.BeginBlock, $body.EndBlock)
    if ($First) { $named += $body.ProcessBlock }
    foreach ($block in $named) {
        if ($null -eq $block) { continue }
        $returns = $block.Find({ param($node)
            if ($node -isnot [System.Management.Automation.Language.ReturnStatementAst]) { return $false }
            $owner = $node.Parent
            while ($owner -isnot [System.Management.Automation.Language.ScriptBlockAst]) { $owner = $owner.Parent }
            [object]::ReferenceEquals($owner, $body) }, $true)
        if ($null -eq $returns -and (Test-BlockEnds $block $Ending @('exit', 'throw', 'break', 'continue'))) { return $true }
    }
    return $false
}

function Test-LeavesLoop {
    <#
    .SYNOPSIS
        Whether a break or continue sits anywhere in this node, which may leave the loop or switch
        it belongs to early.
    #>
    param($Node)

    return $null -ne $Node.Find({ param($node)
        $node -is [System.Management.Automation.Language.BreakStatementAst] -or
        $node -is [System.Management.Automation.Language.ContinueStatementAst] }, $true)
}

function Get-SinglePipelineExpression {
    <#
    .SYNOPSIS
        The expression a pipeline of one plain expression holds, else the node itself.
    #>
    param($Node)

    if ($Node -is [System.Management.Automation.Language.PipelineAst] -and $Node.PipelineElements.Count -eq 1 -and
            $Node.PipelineElements[0] -is [System.Management.Automation.Language.CommandExpressionAst]) {
        return $Node.PipelineElements[0].Expression
    }
    return $Node
}

function Test-EmptyArray {
    <#
    .SYNOPSIS
        Whether an expression is a literal @(), which holds no item, in parentheses or not.
    #>
    param($Node)

    $expression = Get-SinglePipelineExpression $Node
    while ($expression -is [System.Management.Automation.Language.ParenExpressionAst]) {
        $expression = Get-SinglePipelineExpression $expression.Pipeline
    }
    return $expression -is [System.Management.Automation.Language.ArrayExpressionAst] -and
        $expression.SubExpression.Statements.Count -eq 0
}

function Test-SwitchClauseMatches {
    <#
    .SYNOPSIS
        $true when a switch clause's label certainly matches the switch's subject, $false when it
        certainly doesn't, and $null when that isn't known: a label or subject that isn't a
        constant (Get-ConstantValue), a script block label, a switch over a file's lines, or one
        told both -Regex and -Wildcard.
    .DESCRIPTION
        The two constants go through a switch of the same kind here, so the answer is
        PowerShell's own: a number label doesn't match the string '01', and case only counts
        with -CaseSensitive.
    #>
    param([System.Management.Automation.Language.SwitchStatementAst]$Statement, $Label)

    $flags = $Statement.Flags
    $regex = ($flags -band [System.Management.Automation.Language.SwitchFlags]::Regex) -ne 0
    $wildcard = ($flags -band [System.Management.Automation.Language.SwitchFlags]::Wildcard) -ne 0
    $caseSensitive = ($flags -band [System.Management.Automation.Language.SwitchFlags]::CaseSensitive) -ne 0
    if (($flags -band [System.Management.Automation.Language.SwitchFlags]::File) -ne 0 -or ($regex -and $wildcard) -or
        $Label -is [System.Management.Automation.Language.ScriptBlockExpressionAst]) { return $null }
    $subject = Get-ConstantValue $Statement.Condition
    $pattern = Get-ConstantValue $Label
    if ($null -eq $subject -or $null -eq $pattern) { return $null }
    $value = $subject.Value
    $want = $pattern.Value
    $matched = $false
    try {
        if ($regex -and $caseSensitive) { switch -Regex -CaseSensitive ($value) { $want { $matched = $true } } }
        elseif ($regex) { switch -Regex ($value) { $want { $matched = $true } } }
        elseif ($wildcard -and $caseSensitive) { switch -Wildcard -CaseSensitive ($value) { $want { $matched = $true } } }
        elseif ($wildcard) { switch -Wildcard ($value) { $want { $matched = $true } } }
        elseif ($caseSensitive) { switch -CaseSensitive ($value) { $want { $matched = $true } } }
        else { switch ($value) { $want { $matched = $true } } }
    } catch {
        # A label that isn't a valid regex. Not knowing is the answer that never drops code.
        return $null
    }
    return $matched
}

function Test-BlockEnds {
    <#
    .SYNOPSIS
        Whether one of a block's statements ends it (Test-StatementEnds), with what leaves the
        block counted as -Escapes says.
    #>
    param($Block, [System.Collections.Generic.HashSet[string]]$Ending,
        [string[]]$Escapes = @('exit', 'return', 'throw', 'break', 'continue'))

    foreach ($statement in $Block.Statements) {
        if (Test-StatementEnds $statement $Ending $Escapes) { return $true }
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
            # Called by the name without its scope, as 'function script:Stop-Now' is.
            $name = Get-FunctionName $function
            if ($ending.Contains($name) -or $null -eq $function.Body.EndBlock -or
                $null -ne $function.Body.Find({ param($node)
                    $node -is [System.Management.Automation.Language.ReturnStatementAst] }, $true)) { continue }
            if (Test-BlockEnds $function.Body.EndBlock $ending) {
                [void]$ending.Add($name)
                $grew = $true
            }
        }
    } while ($grew)
    return , $ending
}

function Get-FunctionName {
    <#
    .SYNOPSIS
        The name a function definition is called by: its own, without a scope such as script:.
    #>
    param([System.Management.Automation.Language.FunctionDefinitionAst]$Function)

    return ($Function.Name -split ':')[-1]
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
            if (-not $Called.Contains((Get-FunctionName $parent))) { return $false }
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
        } elseif ($parent -is [System.Management.Automation.Language.ForEachStatementAst]) {
            # PowerShell runs a foreach body for no item of a literal @() and none of $null.
            if ([object]::ReferenceEquals($parent.Body, $child)) {
                $collection = Get-ConstantValue $parent.Condition
                if ((Test-EmptyArray $parent.Condition) -or ($null -ne $collection -and $null -eq $collection.Value)) { return $false }
            }
        } elseif ($parent -is [System.Management.Automation.Language.SwitchStatementAst]) {
            # A literal @() runs nothing in the switch, a clause whose label can't match the
            # subject never runs, and the default doesn't once a clause certainly does.
            if (-not [object]::ReferenceEquals($parent.Condition, $child)) {
                if (Test-EmptyArray $parent.Condition) { return $false }
                foreach ($clause in $parent.Clauses) {
                    $match = Test-SwitchClauseMatches $parent $clause.Item1
                    if (([object]::ReferenceEquals($clause.Item2, $child) -and $match -eq $false) -or
                        ([object]::ReferenceEquals($parent.Default, $child) -and $match -eq $true)) { return $false }
                }
            }
        } elseif ($parent -is [System.Management.Automation.Language.TryStatementAst]) {
            # A catch only runs once the body throws, which an empty body never does, nor one that
            # jumps out before anything else in it runs.
            if ($child -is [System.Management.Automation.Language.CatchClauseAst] -and
                    ($parent.Body.Statements.Count -eq 0 -or (Test-PlainJump $parent.Body.Statements[0]))) { return $false }
        } elseif ($parent -is [System.Management.Automation.Language.BinaryExpressionAst]) {
            # -and never evaluates its right side after a false left one, and -or after a true one.
            if ([object]::ReferenceEquals($parent.Right, $child)) {
                $left = Get-ConstantTruth $parent.Left
                if (($parent.Operator -eq [System.Management.Automation.Language.TokenKind]::And -and $left -eq $false) -or
                    ($parent.Operator -eq [System.Management.Automation.Language.TokenKind]::Or -and $left -eq $true)) { return $false }
            }
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

    # A name defined twice, with a scope or without, counts as never called: which body a call
    # runs depends on which definition ran last before it.
    $definitions = [System.Collections.Generic.Dictionary[string, int]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($function in $Ast.FindAll({ param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true)) {
        $name = Get-FunctionName $function
        $count = 0
        [void]$definitions.TryGetValue($name, [ref]$count)
        $definitions[$name] = $count + 1
    }
    $commands = @($Ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] }, $true))
    $called = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    do {
        $grew = $false
        foreach ($command in $commands) {
            $name = $command.GetCommandName()
            $count = 0
            if (-not $name -or -not $definitions.TryGetValue($name, [ref]$count) -or $count -ne 1 -or
                $called.Contains($name)) { continue }
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
