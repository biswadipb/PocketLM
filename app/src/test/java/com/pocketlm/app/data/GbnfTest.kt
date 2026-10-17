package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GbnfTest {

    private fun problem(text: String) = Gbnf.problem(text.trimIndent())

    @Test
    fun theBuiltInExamplesAreAccepted() {
        for (example in Gbnf.examples) assertNull(example.name, Gbnf.problem(example.grammar))
    }

    @Test
    fun theJsonGrammarTheAppAlreadyUsesIsAccepted() {
        assertNull(Gbnf.problem(JsonGrammar.gbnf))
    }

    @Test
    fun aRuleMayContinueAfterAnOpenBracketAndAfterABar() {
        assertNull(problem("""
            root ::= (
              "a" |
              "b"
            ) tail
            tail ::= "x"  # a comment after the rule
        """))
    }

    @Test
    fun everyTokenFormIsAccepted() {
        assertNull(problem("""
            root ::= word{2,4} ws? .* <[1234]> !<[5]> [^a-z\x00-\x1F]
            word ::= [a-zA-Z]+
            ws ::= " " | "\t" | "\n" | "é" | "\\" | "\""
        """))
    }

    @Test
    fun emptyTextIsAskedFor() {
        assertEquals("Enter a grammar, or turn it off.", Gbnf.problem(""))
        assertEquals("Enter a grammar, or turn it off.", Gbnf.problem("   \n  "))
        assertEquals("Enter a grammar, or turn it off.", Gbnf.problem("# only a comment"))
    }

    @Test
    fun aGrammarMustHaveARootRule() {
        assertTrue(problem("""answer ::= "yes"""")!!.contains("root"))
    }

    @Test
    fun aRuleUsedButNeverWrittenIsNamed() {
        val message = problem("""
            root ::= greeting "!"
        """)!!
        assertTrue(message, message.contains("greeting"))
        assertTrue(message, message.startsWith("Line 1:"))
    }

    @Test
    fun aRuleDefinedTwiceIsRefused() {
        val message = problem("""
            root ::= "a"
            root ::= "b"
        """)!!
        assertTrue(message, message.contains("already defined on line 1"))
    }

    @Test
    fun anOpenQuoteIsReportedOnItsOwnLine() {
        val message = problem("""
            root ::= "yes" other
            other ::= "no
        """)!!
        assertTrue(message, message.startsWith("Line 2:"))
        assertTrue(message, message.contains("not closed"))
    }

    @Test
    fun anOpenCharacterSetIsReported() {
        assertTrue(problem("root ::= [a-z")!!.contains("not closed"))
        assertTrue(problem("root ::= []")!!.contains("empty"))
    }

    @Test
    fun anUnknownEscapeIsExplained() {
        val message = problem("""root ::= "a\.b"""")!!
        assertTrue(message, message.contains("Unknown escape \\."))
        assertTrue(problem("""root ::= "\x4""")!!.isNotEmpty())
        assertTrue(problem("""root ::= "\xZZ"""")!!.contains("hexadecimal"))
    }

    @Test
    fun repeatCountsAreChecked() {
        assertTrue(problem("""root ::= "a"{x}""")!!.contains("repeat count"))
        assertTrue(problem("""root ::= "a"{5,2}""")!!.contains("larger"))
        assertTrue(problem("""root ::= "a"{3000}""")!!.contains("2000"))
        assertTrue(problem("""root ::= "a"{3""")!!.contains("not closed"))
        assertNull(problem("""root ::= "a"{3} "b"{1,} "c"{ 2 , 3 }"""))
    }

    @Test
    fun bracketsMustMatch() {
        assertTrue(problem("""root ::= ("a" | "b"""")!!.contains("never closed"))
        assertTrue(problem("""root ::= "a" )""")!!.contains("no ("))
    }

    @Test
    fun anOperatorNeedsSomethingToRepeat() {
        assertTrue(problem("""root ::= * "a"""")!!.contains("Nothing to repeat"))
        assertTrue(problem("""root ::= "a" | + "b"""")!!.contains("Nothing to repeat"))
        assertTrue(problem("""root ::= "a"+?""")!!.contains("Nothing to repeat"))
    }

    @Test
    fun aRuleNeedsABody() {
        assertTrue(problem("""
            root ::= "a" b
            b ::=
        """)!!.contains("nothing after"))
    }

    @Test
    fun aLooseWordOrSymbolIsAMistake() {
        assertTrue(problem("""root = "yes"""")!!.contains("Unexpected '='"))
        assertTrue(problem("""yes or no""")!!.contains("list of rules"))
        assertTrue(problem("""root ::= "a", "b"""")!!.contains("Unexpected ','"))
    }

    @Test
    fun aVeryLongGrammarIsRefused() {
        assertEquals("The grammar is too long.", Gbnf.problem("root ::= \"a\"\n" + "# x".repeat(Gbnf.MAX_CHARS)))
        assertNotNull(Gbnf.problem("x"))
    }
}
